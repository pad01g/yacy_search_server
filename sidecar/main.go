// yacy-sidecar: NAT traversal for YaCy peers with go-libp2p (see docs/trust-and-nat.md, section 6).
//
// The sidecar runs next to a YaCy peer and uses the same Ed25519 key, so its libp2p peer id is derived from the
// key of the YaCy seed. It
//   - accepts libp2p streams (protocol /yacy/http/1.0.0) and passes the HTTP requests in them to the sidecar
//     connector of the local YaCy, restricted to the peer-to-peer endpoints (/yacy/..., Solr select);
//   - opens a local tunnel port per remote peer on request (GET /tunnel/<peer id>); HTTP sent to the tunnel is
//     carried to that peer, through a circuit relay when the peer is behind a NAT;
//   - reports its peer id, reachability (AutoNAT) and relay addresses on GET /status;
//   - with -relay-service, acts as a circuit relay v2 for others.
//
// The control API (/status, /tunnel) listens on the loopback interface and requires the token YaCy writes to
// DATA/SETTINGS/sidecar.token, so that neither other local processes nor web pages can drive it.
package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/subtle"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/event"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/peerstore"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/net/gostream"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	ma "github.com/multiformats/go-multiaddr"
)

const yacyProtocol = protocol.ID("/yacy/http/1.0.0")

const (
	// maxTunnels bounds the number of local tunnel ports (one per remote peer); the least recently used is closed
	maxTunnels = 256
	// tunnelIdle closes tunnels that were not used for this long (YaCy asks again after 5 minutes)
	tunnelIdle = 15 * time.Minute
	// maxRequestBody bounds requests carried to the local YaCy (index transfers are the largest)
	maxRequestBody = 32 << 20
	// maxResponseBody bounds answers of remote peers read through a tunnel
	maxResponseBody = 32 << 20
	// requestDeadline bounds one request through a tunnel, including the answer
	requestDeadline = 60 * time.Second
	// perPeerStreams bounds concurrent requests of one remote peer to the local YaCy
	perPeerStreams = 8
)

// allowedPath: only the peer-to-peer interface of YaCy is reachable through the sidecar, never the admin pages
var allowedPath = regexp.MustCompile(`^/(yacy/[A-Za-z0-9]+\.(html|json|xml)|solr/([a-z0-9_]+/)?select)$`)

type tunnelEntry struct {
	port     int
	listener net.Listener
	lastUsed time.Time
}

type sidecar struct {
	host     host.Host
	yacy     *url.URL
	token    string
	boot     string
	relayMu  sync.RWMutex
	relays   []peer.AddrInfo
	mu       sync.Mutex
	tunnels  map[peer.ID]*tunnelEntry
	reach    network.Reachability
	reachMu  sync.RWMutex
	rsvpMu   sync.Mutex
	rsvp     map[peer.ID][]string
	streamMu sync.Mutex
	streams  map[string]int
}

func main() {
	keyFile := flag.String("key", "", "PEM (PKCS#8) Ed25519 key of the YaCy peer; empty: random key")
	keyCreate := flag.Bool("key-create", false, "create the key file if it does not exist (for a relay with a stable id)")
	tokenFile := flag.String("token-file", "", "file with the token that clients of the control API must send (X-YaCy-Sidecar-Token)")
	listen := flag.String("listen", "/ip4/0.0.0.0/tcp/4001", "libp2p listen addresses, comma separated")
	httpAddr := flag.String("http", "127.0.0.1:8095", "address of the control API (/status, /tunnel/<id>)")
	yacyURL := flag.String("yacy", "http://127.0.0.1:8096", "base URL of the sidecar connector of the local YaCy")
	relaysFlag := flag.String("relay", "", "relays, comma separated: multiaddrs with /p2p/<id>, or http://host:port of a relay sidecar's control API")
	relayService := flag.Bool("relay-service", false, "act as a circuit relay for other peers")
	relayAllow := flag.String("relay-allow", "", "file with libp2p peer ids (one per line) allowed to use the relay; empty: everybody")
	reachability := flag.String("reachability", "auto", "auto, public or private (override AutoNAT)")
	keyWait := flag.Duration("key-wait", 5*time.Minute, "how long to wait for the key and token files to appear")
	flag.Parse()

	priv, err := loadKey(*keyFile, *keyCreate, *keyWait)
	if err != nil {
		log.Fatalf("key: %v", err)
	}
	token, err := loadToken(*tokenFile, *keyWait)
	if err != nil {
		log.Fatalf("token: %v", err)
	}
	yacy, err := url.Parse(*yacyURL)
	if err != nil {
		log.Fatalf("yacy url: %v", err)
	}

	opts := []libp2p.Option{
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(strings.Split(*listen, ",")...),
		libp2p.EnableRelay(),
		libp2p.EnableHolePunching(),
		libp2p.EnableNATService(),
	}
	if *relayService {
		res := relay.DefaultResources()
		// the default limit (2 minutes, 128 KiB per circuit) is too small for search answers and index transfers;
		// every YaCy request is a new stream, so a circuit that reached the limit is simply opened again
		res.Limit = &relay.RelayLimit{Duration: 10 * time.Minute, Data: 16 << 20}
		relayOpts := []relay.Option{relay.WithResources(res)}
		if *relayAllow != "" {
			acl, err := loadACL(*relayAllow)
			if err != nil {
				log.Fatalf("relay allow list: %v", err)
			}
			relayOpts = append(relayOpts, relay.WithACL(acl))
		}
		opts = append(opts, libp2p.EnableRelayService(relayOpts...))
	}
	switch *reachability {
	case "public":
		opts = append(opts, libp2p.ForceReachabilityPublic())
	case "private":
		opts = append(opts, libp2p.ForceReachabilityPrivate())
	}
	h, err := libp2p.New(opts...)
	if err != nil {
		log.Fatalf("libp2p: %v", err)
	}
	s := &sidecar{
		host: h, yacy: yacy, token: token, boot: randomID(),
		tunnels: map[peer.ID]*tunnelEntry{}, reach: network.ReachabilityUnknown,
		rsvp: map[peer.ID][]string{}, streams: map[string]int{},
	}
	switch *reachability {
	case "public":
		s.reach = network.ReachabilityPublic
	case "private":
		s.reach = network.ReachabilityPrivate
	}
	log.Printf("peer id %s, listening on %v, relay service %v", h.ID(), h.Addrs(), *relayService)

	go s.watchReachability()
	go s.maintainRelays(*relaysFlag, !*relayService)
	go s.expireTunnels()
	if !*relayService {
		go s.serveStreams()
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/status", s.guard(*httpAddr, s.handleStatus))
	if !*relayService {
		mux.HandleFunc("/tunnel/", s.guard(*httpAddr, s.handleTunnel))
	}
	log.Fatal((&http.Server{Addr: *httpAddr, Handler: mux, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second}).ListenAndServe())
}

func randomID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// waitForFile waits for YaCy to create a file on its first start
func waitForFile(file string, wait time.Duration) ([]byte, error) {
	deadline := time.Now().Add(wait)
	for {
		data, err := os.ReadFile(file)
		if err == nil {
			return data, nil
		}
		if time.Now().After(deadline) || !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
		time.Sleep(2 * time.Second)
	}
}

func loadKey(file string, create bool, wait time.Duration) (crypto.PrivKey, error) {
	if file == "" {
		priv, _, err := crypto.GenerateEd25519Key(nil)
		return priv, err
	}
	if create {
		if _, err := os.Stat(file); errors.Is(err, os.ErrNotExist) {
			_, ek, err := ed25519.GenerateKey(nil)
			if err != nil {
				return nil, err
			}
			der, err := x509.MarshalPKCS8PrivateKey(ek)
			if err != nil {
				return nil, err
			}
			if err := os.WriteFile(file, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
				return nil, err
			}
		}
	}
	data, err := waitForFile(file, wait)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("no PEM block in " + file)
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ek, ok := k.(ed25519.PrivateKey)
	if !ok {
		return nil, errors.New("not an Ed25519 key: " + file)
	}
	return crypto.UnmarshalEd25519PrivateKey(ek)
}

func loadToken(file string, wait time.Duration) (string, error) {
	if file == "" {
		return "", nil
	}
	data, err := waitForFile(file, wait)
	if err != nil {
		return "", err
	}
	t := strings.TrimSpace(string(data))
	if len(t) < 16 {
		return "", errors.New("token too short in " + file)
	}
	return t, nil
}

// acl allows reservations and circuits only for the listed peers
type acl map[peer.ID]bool

func (a acl) AllowReserve(p peer.ID, _ ma.Multiaddr) bool { return a[p] }
func (a acl) AllowConnect(src peer.ID, _ ma.Multiaddr, dest peer.ID) bool {
	return a[src] || a[dest]
}

func loadACL(file string) (acl, error) {
	f, err := os.Open(file)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	a := acl{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		id, err := peer.Decode(line)
		if err != nil {
			return nil, fmt.Errorf("%s: %v", line, err)
		}
		a[id] = true
	}
	return a, sc.Err()
}

// guard protects the control API: only requests with the token, addressed to the loopback host name we listen on,
// and not sent by a web page (Origin / Sec-Fetch-Site) are served. This stops local web pages and DNS rebinding.
// A relay publishes its /status on all interfaces (other sidecars read its peer id there); then the host is not
// checked, and the relay offers no /tunnel.
func (s *sidecar) guard(listenAddr string, next http.HandlerFunc) http.HandlerFunc {
	host, port, _ := net.SplitHostPort(listenAddr)
	public := host == "0.0.0.0" || host == "" || host == "::"
	return func(w http.ResponseWriter, r *http.Request) {
		if !public && !isLoopbackHost(r.Host, port) {
			http.Error(w, "bad host", http.StatusForbidden)
			return
		}
		if r.Header.Get("Origin") != "" || r.Header.Get("Sec-Fetch-Site") == "cross-site" {
			http.Error(w, "not for web pages", http.StatusForbidden)
			return
		}
		if s.token != "" && subtle.ConstantTimeCompare([]byte(r.Header.Get("X-YaCy-Sidecar-Token")), []byte(s.token)) != 1 {
			http.Error(w, "token required", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func isLoopbackHost(hostHeader, port string) bool {
	h, p, err := net.SplitHostPort(hostHeader)
	if err != nil {
		return false
	}
	return p == port && (h == "127.0.0.1" || h == "localhost" || h == "::1")
}

// maintainRelays resolves the configured relays and keeps them up to date: relay sidecars given by URL may restart
// with a new peer id or address. Resolution does not block the start of the control API.
func (s *sidecar) maintainRelays(list string, reserve bool) {
	started := map[peer.ID]bool{}
	for {
		relays := resolveRelays(list)
		if len(relays) > 0 {
			s.relayMu.Lock()
			s.relays = relays
			s.relayMu.Unlock()
		}
		for _, r := range relays {
			if started[r.ID] {
				continue
			}
			started[r.ID] = true
			log.Printf("relay %s at %v", r.ID, r.Addrs)
			go s.keepConnected(r.ID)
			if reserve {
				go s.reserveLoop(r.ID)
			}
		}
		if len(relays) == 0 && list != "" {
			time.Sleep(5 * time.Second)
			continue
		}
		time.Sleep(60 * time.Second)
	}
}

func (s *sidecar) currentRelays() []peer.AddrInfo {
	s.relayMu.RLock()
	defer s.relayMu.RUnlock()
	return append([]peer.AddrInfo(nil), s.relays...)
}

func (s *sidecar) relayInfo(id peer.ID) (peer.AddrInfo, bool) {
	for _, r := range s.currentRelays() {
		if r.ID == id {
			return r, true
		}
	}
	return peer.AddrInfo{}, false
}

// resolveRelays accepts multiaddrs and URLs of relay sidecars (whose /status gives peer id and addresses)
func resolveRelays(list string) []peer.AddrInfo {
	var out []peer.AddrInfo
	for _, r := range strings.Split(list, ",") {
		r = strings.TrimSpace(r)
		if r == "" {
			continue
		}
		if strings.HasPrefix(r, "http://") || strings.HasPrefix(r, "https://") {
			info, err := relayFromStatus(r)
			if err != nil {
				log.Printf("relay %s: %v", r, err)
				continue
			}
			out = append(out, *info)
			continue
		}
		info, err := peer.AddrInfoFromString(r)
		if err != nil {
			log.Printf("ignoring relay %s: %v", r, err)
			continue
		}
		out = append(out, *info)
	}
	return out
}

type status struct {
	PeerID       string   `json:"peerId"`
	Boot         string   `json:"boot"`
	Reachability string   `json:"reachability"`
	Addrs        []string `json:"addrs"`
	RelayAddrs   []string `json:"relayAddrs"`
	Relays       []string `json:"relays"`
	Tunnels      int      `json:"tunnels"`
}

func relayFromStatus(base string) (*peer.AddrInfo, error) {
	c := http.Client{Timeout: 3 * time.Second}
	res, err := c.Get(strings.TrimRight(base, "/") + "/status")
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	var st status
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<16)).Decode(&st); err != nil {
		return nil, err
	}
	id, err := peer.Decode(st.PeerID)
	if err != nil {
		return nil, err
	}
	info := &peer.AddrInfo{ID: id}
	for _, a := range st.Addrs {
		m, err := ma.NewMultiaddr(a)
		if err != nil || isLoopback(m) {
			continue
		}
		info.Addrs = append(info.Addrs, m)
	}
	if len(info.Addrs) == 0 {
		return nil, errors.New("relay reports no usable address")
	}
	return info, nil
}

func isLoopback(m ma.Multiaddr) bool {
	s := m.String()
	return strings.HasPrefix(s, "/ip4/127.") || strings.HasPrefix(s, "/ip6/::1/")
}

func (s *sidecar) watchReachability() {
	sub, err := s.host.EventBus().Subscribe(new(event.EvtLocalReachabilityChanged))
	if err != nil {
		log.Printf("reachability events: %v", err)
		return
	}
	for e := range sub.Out() {
		r := e.(event.EvtLocalReachabilityChanged).Reachability
		s.reachMu.Lock()
		s.reach = r
		s.reachMu.Unlock()
		log.Printf("reachability: %s", r)
	}
}

func (s *sidecar) reachability() network.Reachability {
	s.reachMu.RLock()
	defer s.reachMu.RUnlock()
	return s.reach
}

// keepConnected keeps a connection to a relay, which AutoNAT and the reservation need
func (s *sidecar) keepConnected(id peer.ID) {
	for {
		if r, ok := s.relayInfo(id); ok && s.host.Network().Connectedness(id) != network.Connected {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			if err := s.host.Connect(ctx, r); err != nil {
				log.Printf("relay %s: %v", id, err)
			}
			cancel()
		}
		time.Sleep(15 * time.Second)
	}
}

// reserveLoop keeps a reservation on the relay while this peer is not known to be publicly reachable.
// AutoRelay only reserves after AutoNAT reported "private", and AutoNAT cannot decide as long as the peer only
// has private addresses (or needs several minutes); YaCy decides from its own hello results whether it uses the
// relay addresses.
func (s *sidecar) reserveLoop(id peer.ID) {
	for {
		r, ok := s.relayInfo(id)
		if !ok || s.reachability() == network.ReachabilityPublic {
			s.dropReservation(id)
			time.Sleep(30 * time.Second)
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		res, err := client.Reserve(ctx, s.host, r)
		cancel()
		if err != nil {
			log.Printf("reservation on relay %s: %v", id, err)
			s.dropReservation(id) // do not announce circuit addresses that may no longer work
			time.Sleep(15 * time.Second)
			continue
		}
		// the relay only announces public addresses in the reservation; in private networks use the ones we dialed
		relayAddrs := res.Addrs
		if len(relayAddrs) == 0 {
			relayAddrs = r.Addrs
		}
		var addrs []string
		for _, a := range relayAddrs {
			if isLoopback(a) {
				continue
			}
			addrs = append(addrs, a.String()+"/p2p/"+id.String()+"/p2p-circuit/p2p/"+s.host.ID().String())
		}
		s.rsvpMu.Lock()
		first := len(s.rsvp[id]) == 0
		s.rsvp[id] = addrs
		s.rsvpMu.Unlock()
		if first {
			log.Printf("reserved a slot on relay %s until %s: %v", id, res.Expiration.Format(time.RFC3339), addrs)
		}
		// refresh well before the reservation expires
		wait := time.Until(res.Expiration) - 2*time.Minute
		if wait > 5*time.Minute {
			wait = 5 * time.Minute
		}
		if wait < 30*time.Second {
			wait = 30 * time.Second
		}
		time.Sleep(wait)
	}
}

func (s *sidecar) dropReservation(id peer.ID) {
	s.rsvpMu.Lock()
	delete(s.rsvp, id)
	s.rsvpMu.Unlock()
}

func (s *sidecar) handleStatus(w http.ResponseWriter, _ *http.Request) {
	st := status{PeerID: s.host.ID().String(), Boot: s.boot, Reachability: strings.ToLower(s.reachability().String())}
	seen := map[string]bool{}
	for _, a := range s.host.Addrs() {
		full := a.String() + "/p2p/" + s.host.ID().String()
		if strings.Contains(a.String(), "/p2p-circuit") {
			if !seen[full] {
				st.RelayAddrs = append(st.RelayAddrs, full)
				seen[full] = true
			}
		} else {
			st.Addrs = append(st.Addrs, a.String())
		}
	}
	s.rsvpMu.Lock()
	for _, addrs := range s.rsvp {
		for _, a := range addrs {
			if !seen[a] {
				st.RelayAddrs = append(st.RelayAddrs, a)
				seen[a] = true
			}
		}
	}
	s.rsvpMu.Unlock()
	for _, r := range s.currentRelays() {
		st.Relays = append(st.Relays, r.ID.String())
	}
	s.mu.Lock()
	st.Tunnels = len(s.tunnels)
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(st)
}

// handleTunnel returns the local port of the tunnel to a peer, opening it on first use. The optional addrs
// parameter carries the circuit addresses from the peer's seed ('|' separated).
func (s *sidecar) handleTunnel(w http.ResponseWriter, r *http.Request) {
	id, err := peer.Decode(strings.TrimPrefix(r.URL.Path, "/tunnel/"))
	if err != nil {
		http.Error(w, "bad peer id", http.StatusBadRequest)
		return
	}
	if id == s.host.ID() {
		http.Error(w, "that is me", http.StatusBadRequest)
		return
	}
	s.addPeerAddrs(id, r.URL.Query().Get("addrs"))
	port, err := s.tunnel(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, "{\"port\":%d}\n", port)
}

// addPeerAddrs adds circuit addresses announced in a seed: only relay circuits that end in the peer's own id
func (s *sidecar) addPeerAddrs(id peer.ID, list string) {
	for _, info := range circuitAddrs(id, list) {
		s.host.Peerstore().AddAddrs(id, info.Addrs, peerstore.TempAddrTTL)
	}
}

// circuitAddrs parses at most 8 '|' separated circuit addresses of the peer and drops everything else
func circuitAddrs(id peer.ID, list string) []peer.AddrInfo {
	var out []peer.AddrInfo
	for _, a := range strings.Split(list, "|") {
		a = strings.TrimSpace(a)
		if a == "" || len(out) >= 8 || !strings.Contains(a, "/p2p-circuit/") {
			continue
		}
		m, err := ma.NewMultiaddr(a)
		if err != nil {
			continue
		}
		info, err := peer.AddrInfoFromP2pAddr(m)
		if err != nil || info.ID != id {
			continue
		}
		out = append(out, *info)
	}
	return out
}

func (s *sidecar) tunnel(id peer.ID) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if t, ok := s.tunnels[id]; ok {
		t.lastUsed = time.Now()
		return t.port, nil
	}
	if len(s.tunnels) >= maxTunnels {
		s.closeOldestLocked()
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := l.Addr().(*net.TCPAddr).Port
	entry := &tunnelEntry{port: port, listener: l, lastUsed: time.Now()}
	s.tunnels[id] = entry
	proxy := httputil.NewSingleHostReverseProxy(&url.URL{Scheme: "http", Host: "peer"})
	proxy.Transport = &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return s.dial(ctx, id)
		},
		DisableKeepAlives:     true,
		ResponseHeaderTimeout: 30 * time.Second,
	}
	proxy.ModifyResponse = func(res *http.Response) error {
		res.Body = &limitedBody{ReadCloser: res.Body, remaining: maxResponseBody}
		return nil
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, err error) {
		log.Printf("tunnel to %s: %v", id, err)
		http.Error(w, "peer not reachable: "+err.Error(), http.StatusBadGateway)
	}
	portStr := fmt.Sprint(port)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// only YaCy uses the tunnel: no web pages (DNS rebinding), and the Host must be the loopback address
		if !isLoopbackHost(r.Host, portStr) || r.Header.Get("Origin") != "" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		s.mu.Lock()
		entry.lastUsed = time.Now()
		s.mu.Unlock()
		ctx, cancel := context.WithTimeout(r.Context(), requestDeadline)
		defer cancel()
		proxy.ServeHTTP(w, r.WithContext(ctx))
	})
	go func() {
		srv := &http.Server{Handler: handler, ReadHeaderTimeout: 30 * time.Second, ReadTimeout: requestDeadline, IdleTimeout: 60 * time.Second}
		log.Printf("tunnel to %s on 127.0.0.1:%d", id, port)
		if err := srv.Serve(l); err != nil && !errors.Is(err, net.ErrClosed) {
			log.Printf("tunnel to %s closed: %v", id, err)
		}
	}()
	return port, nil
}

func (s *sidecar) closeOldestLocked() {
	ids := make([]peer.ID, 0, len(s.tunnels))
	for id := range s.tunnels {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return s.tunnels[ids[i]].lastUsed.Before(s.tunnels[ids[j]].lastUsed) })
	if len(ids) > 0 {
		s.tunnels[ids[0]].listener.Close()
		delete(s.tunnels, ids[0])
	}
}

// expireTunnels closes tunnels that were not used for a while
func (s *sidecar) expireTunnels() {
	for {
		time.Sleep(time.Minute)
		s.mu.Lock()
		for id, t := range s.tunnels {
			if time.Since(t.lastUsed) > tunnelIdle {
				t.listener.Close()
				delete(s.tunnels, id)
			}
		}
		s.mu.Unlock()
	}
}

// limitedBody ends an answer of a remote peer after maxResponseBody bytes
type limitedBody struct {
	io.ReadCloser
	remaining int64
}

func (b *limitedBody) Read(p []byte) (int, error) {
	if b.remaining <= 0 {
		return 0, errors.New("answer too large")
	}
	if int64(len(p)) > b.remaining {
		p = p[:b.remaining]
	}
	n, err := b.ReadCloser.Read(p)
	b.remaining -= int64(n)
	return n, err
}

// dial opens a stream to the peer, directly if we know an address, otherwise through the relays
func (s *sidecar) dial(ctx context.Context, id peer.ID) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	if s.host.Network().Connectedness(id) != network.Connected {
		for _, r := range s.currentRelays() {
			circuit, err := ma.NewMultiaddr("/p2p/" + r.ID.String() + "/p2p-circuit")
			if err != nil {
				continue
			}
			for _, ra := range r.Addrs {
				s.host.Peerstore().AddAddr(id, ra.Encapsulate(circuit), peerstore.TempAddrTTL)
			}
		}
		if err := s.host.Connect(network.WithAllowLimitedConn(ctx, "yacy"), peer.AddrInfo{ID: id}); err != nil {
			return nil, err
		}
	}
	return gostream.Dial(network.WithAllowLimitedConn(ctx, "yacy"), s.host, id, yacyProtocol)
}

// serveStreams passes HTTP requests from other peers to the local YaCy
func (s *sidecar) serveStreams() {
	l, err := gostream.Listen(s.host, yacyProtocol)
	if err != nil {
		log.Fatalf("stream listener: %v", err)
	}
	srv := &http.Server{
		Handler:           s.limitPerPeer(yacyHandler(s.yacy)),
		ReadHeaderTimeout: 30 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	log.Fatal(srv.Serve(l))
}

// limitPerPeer bounds the concurrent requests of one remote peer (the remote address of a stream is its peer id)
func (s *sidecar) limitPerPeer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.streamMu.Lock()
		if s.streams[r.RemoteAddr] >= perPeerStreams {
			s.streamMu.Unlock()
			http.Error(w, "too many concurrent requests", http.StatusTooManyRequests)
			return
		}
		s.streams[r.RemoteAddr]++
		s.streamMu.Unlock()
		defer func() {
			s.streamMu.Lock()
			if s.streams[r.RemoteAddr]--; s.streams[r.RemoteAddr] <= 0 {
				delete(s.streams, r.RemoteAddr)
			}
			s.streamMu.Unlock()
		}()
		next.ServeHTTP(w, r)
	})
}

// yacyHandler passes requests from other peers to the local YaCy: only the peer-to-peer paths, with a size limit,
// and without any header that YaCy could take as the client address
func yacyHandler(yacy *url.URL) http.Handler {
	proxy := httputil.NewSingleHostReverseProxy(yacy)
	base := proxy.Director
	proxy.Director = func(r *http.Request) {
		base(r)
		r.Host = yacy.Host
		// the libp2p remote address is not an IP; YaCy must not take it (or anything a remote peer puts into the
		// request) as the client address. YaCy trusts X-Real-IP from configured reverse proxies, which may include
		// the loopback address this request comes from.
		for _, h := range []string{"X-Real-Ip", "Forwarded", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "Client-Ip", "True-Client-Ip"} {
			r.Header.Del(h)
		}
		r.Header["X-Forwarded-For"] = nil
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowedPath.MatchString(r.URL.Path) || strings.Contains(r.URL.Path, "_p.") {
			http.Error(w, "not available through the sidecar", http.StatusForbidden)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxRequestBody)
		// the remote address of a stream is the libp2p peer id, authenticated by the libp2p handshake
		r.Header.Set("X-YaCy-Libp2p-Peer", r.RemoteAddr)
		proxy.ServeHTTP(w, r)
	})
}
