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
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
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
	"strconv"
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
	// totalStreams bounds concurrent requests of all remote peers: peer ids cost nothing to create
	totalStreams = 64
	// maxRetired bounds tunnels that were pushed out or expired and still hold their port for tunnelRetire
	maxRetired = 256
	// smallRequestBody is the limit for every path but the index transfers
	smallRequestBody = 1 << 20
)

// allowedPath: only the peer-to-peer interface of YaCy is reachable through the sidecar, never the admin pages
var allowedPath = regexp.MustCompile(`^/(yacy/[A-Za-z0-9]+\.(html|json|xml)|solr/([a-z0-9_]+/)?select)$`)

type tunnelEntry struct {
	port     int
	listener net.Listener
	lastUsed time.Time
	// retired tunnels keep their port for a while and answer 410, so that the port is not reused for another peer
	// while YaCy may still have it cached
	retired time.Time
}

// tunnelRetire is how long an evicted tunnel keeps its port; YaCy caches a tunnel for 5 minutes
const tunnelRetire = 10 * time.Minute

type sidecar struct {
	host     host.Host
	priv     crypto.PrivKey
	yacy     *url.URL
	token    string
	boot     string
	nonceMu  sync.Mutex
	nonces   map[string]time.Time
	relayMu  sync.RWMutex
	relays   []peer.AddrInfo
	mu       sync.Mutex
	tunnels  map[peer.ID]*tunnelEntry
	retired  []*tunnelEntry
	reach    network.Reachability
	reachMu  sync.RWMutex
	rsvpMu   sync.Mutex
	rsvp     map[peer.ID][]string
	streamMu sync.Mutex
	streams  map[string]int
	total    int
}

func main() {
	keyFile := flag.String("key", "", "PEM (PKCS#8) Ed25519 key of the YaCy peer; empty: random key")
	keyCreate := flag.Bool("key-create", false, "create the key file if it does not exist (for a relay with a stable id)")
	tokenFile := flag.String("token-file", "", "file with the token: control requests are authenticated with an HMAC of it (X-YaCy-Sidecar-Auth), and the sidecar sends it to the YaCy connector (X-YaCy-Sidecar-Token)")
	listen := flag.String("listen", "/ip4/0.0.0.0/tcp/4001", "libp2p listen addresses, comma separated")
	httpAddr := flag.String("http", "127.0.0.1:8095", "address of the control API (/status, /tunnel/<id>)")
	yacyURL := flag.String("yacy", "http://127.0.0.1:8096", "base URL of the sidecar connector of the local YaCy")
	relaysFlag := flag.String("relay", "", "relays, comma separated: multiaddrs with /p2p/<id>, or http://host:port of a relay sidecar's control API")
	relayService := flag.Bool("relay-service", false, "act as a circuit relay for other peers")
	relayAllow := flag.String("relay-allow", "", "file with libp2p peer ids (one per line) allowed to use the relay")
	relayOpen := flag.Bool("relay-open", false, "let every libp2p peer use the relay (without -relay-allow the relay refuses to start)")
	reachability := flag.String("reachability", "auto", "auto, public or private (override AutoNAT)")
	keyWait := flag.Duration("key-wait", 5*time.Minute, "how long to wait for the key and token files to appear")
	watchdog := flag.Duration("yacy-watchdog", 3*time.Minute, "exit when the YaCy connector was unreachable this long (a restart policy then joins a restarted YaCy again); 0: never")
	flag.Parse()

	priv, err := loadKey(*keyFile, *keyCreate, *keyWait)
	if err != nil {
		log.Fatalf("key: %v", err)
	}
	token, err := loadToken(*tokenFile, *keyWait)
	if err != nil {
		log.Fatalf("token: %v", err)
	}
	if !*relayService {
		// the control API opens tunnels in the name of this peer: never without the token, never on other interfaces
		if token == "" {
			log.Fatalf("-token-file is required (the token YaCy writes to DATA/SETTINGS/sidecar.token)")
		}
		if h, _, err := net.SplitHostPort(*httpAddr); err != nil || (h != "127.0.0.1" && h != "localhost" && h != "::1") {
			log.Fatalf("-http must be a loopback address unless -relay-service is set")
		}
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
		// the default limit (2 minutes, 128 KiB per relayed connection) is too small for search answers and index
		// transfers. The limit counts all streams of a relayed connection; it is kept above the body limits, and a
		// sidecar that hits it closes the connection and dials a new circuit (see dial). Use -relay-allow in open
		// networks, so that the relay does not carry traffic of strangers.
		res.Limit = &relay.RelayLimit{Duration: 30 * time.Minute, Data: 4 * maxResponseBody}
		relayOpts := []relay.Option{relay.WithResources(res)}
		if *relayAllow == "" && !*relayOpen {
			log.Fatal("-relay-service needs -relay-allow <file> or -relay-open: an open relay carries the traffic of every libp2p node")
		}
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
		host: h, priv: priv, yacy: yacy, token: token, boot: randomID(),
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
		if *watchdog > 0 {
			go watchYaCy(yacy.Host, *watchdog)
		}
	}

	mux := http.NewServeMux()
	// /status holds no secret (the same facts are in the seed) and proves with a signature that this sidecar holds the
	// peer key: YaCy checks that before it sends the token, so a process that took the port first learns nothing
	mux.HandleFunc("/status", s.guard(*httpAddr, false, s.handleStatus))
	if !*relayService {
		mux.HandleFunc("/tunnel/", s.guard(*httpAddr, true, s.handleTunnel))
	}
	log.Fatal((&http.Server{Addr: *httpAddr, Handler: mux, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second}).ListenAndServe())
}

// watchYaCy exits when the YaCy connector stays unreachable. A sidecar that shares the network namespace of the YaCy
// container (docker network_mode service:...) is left in a dead namespace when that container restarts; exiting
// lets the restart policy start it again in the new one.
func watchYaCy(hostport string, limit time.Duration) {
	if _, _, err := net.SplitHostPort(hostport); err != nil {
		hostport = net.JoinHostPort(hostport, "80")
	}
	last := time.Now()
	for range time.Tick(15 * time.Second) {
		c, err := net.DialTimeout("tcp", hostport, 5*time.Second)
		if err == nil {
			_ = c.Close()
			last = time.Now()
			continue
		}
		if time.Since(last) > limit {
			log.Fatalf("YaCy at %s unreachable for %v: exiting so that the container restarts", hostport, limit)
		}
	}
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
	deadline := time.Now().Add(wait)
	for {
		data, err := waitForFile(file, time.Until(deadline))
		if err != nil {
			return "", err
		}
		t := strings.TrimSpace(string(data))
		if len(t) >= 16 {
			return t, nil
		}
		// YaCy may be writing it right now
		if time.Now().After(deadline) {
			return "", errors.New("token too short in " + file)
		}
		time.Sleep(time.Second)
	}
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

// guard protects the control API: only requests authenticated with the token, addressed to the loopback host name we
// listen on, and not sent by a web page (Origin / Sec-Fetch-Site) are served. This stops local web pages and DNS rebinding.
// A relay publishes its /status on all interfaces (other sidecars read its peer id there); then the host is not
// checked, and the relay offers no /tunnel.
func (s *sidecar) guard(listenAddr string, needToken bool, next http.HandlerFunc) http.HandlerFunc {
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
		if needToken && s.token != "" {
			if err := s.checkAuth(r, time.Now()); err != nil {
				http.Error(w, err.Error(), http.StatusUnauthorized)
				return
			}
		}
		next(w, r)
	}
}

// requestMessage is what YaCy authenticates with HMAC-SHA256(token) for a control request. The token itself is never
// sent: a process that took the control port while the sidecar was down learns nothing it could use later.
func requestMessage(method, path, nonce string) string {
	return "yacy-sidecar-req-v1|" + method + "|" + path + "|" + nonce
}

func requestMAC(token, method, path, nonce string) string {
	m := hmac.New(sha256.New, []byte(token))
	m.Write([]byte(requestMessage(method, path, nonce)))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// nonceWindow: a nonce is "<unix milliseconds>.<random>"; it is valid this long around its time, and only once
const nonceWindow = 2 * time.Minute

// checkAuth checks the X-YaCy-Sidecar-Auth header: the MAC of method, path and a fresh, unused nonce
func (s *sidecar) checkAuth(r *http.Request, now time.Time) error {
	nonce := r.URL.Query().Get("nonce")
	dot := strings.IndexByte(nonce, '.')
	if len(nonce) < 20 || len(nonce) > 80 || dot < 1 {
		return errors.New("authentication required")
	}
	ms, err := strconv.ParseInt(nonce[:dot], 10, 64)
	if err != nil {
		return errors.New("bad nonce")
	}
	if d := now.Sub(time.UnixMilli(ms)); d > nonceWindow || d < -nonceWindow {
		return errors.New("nonce expired (check the clock)")
	}
	want := requestMAC(s.token, r.Method, r.URL.Path, nonce)
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-YaCy-Sidecar-Auth")), []byte(want)) != 1 {
		return errors.New("authentication failed")
	}
	s.nonceMu.Lock()
	defer s.nonceMu.Unlock()
	if s.nonces == nil {
		s.nonces = map[string]time.Time{}
	}
	for n, t := range s.nonces {
		if now.Sub(t) > 2*nonceWindow {
			delete(s.nonces, n)
		}
	}
	if _, used := s.nonces[nonce]; used {
		return errors.New("nonce used before")
	}
	if len(s.nonces) >= 65536 {
		return errors.New("too many requests")
	}
	s.nonces[nonce] = now
	return nil
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
	lastGood := map[string]peer.AddrInfo{}
	for {
		// keep the last good answer of an entry whose resolution failed this round
		for entry, info := range resolveRelayEntries(list) {
			lastGood[entry] = info
		}
		relays := make([]peer.AddrInfo, 0, len(lastGood))
		for _, info := range lastGood {
			relays = append(relays, info)
		}
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

// resolveRelayEntries accepts multiaddrs and URLs of relay sidecars (whose /status gives peer id and addresses);
// the result maps each configured entry that could be resolved to its relay
func resolveRelayEntries(list string) map[string]peer.AddrInfo {
	out := map[string]peer.AddrInfo{}
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
			out[r] = *info
			continue
		}
		info, err := peer.AddrInfoFromString(r)
		if err != nil {
			log.Printf("ignoring relay %s: %v", r, err)
			continue
		}
		out[r] = *info
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
	// Sig signs statusMessage(nonce, peerId, boot, reachability, relayAddrs) with the peer key when /status is asked
	// with ?nonce=
	Sig string `json:"sig,omitempty"`
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

func (s *sidecar) handleStatus(w http.ResponseWriter, r *http.Request) {
	st := status{PeerID: s.host.ID().String(), Boot: s.boot, Reachability: strings.ToLower(s.reachability().String())}
	nonce := r.URL.Query().Get("nonce")
	if len(nonce) > 64 {
		http.Error(w, "nonce too long", http.StatusBadRequest)
		return
	}
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
	for relayID, addrs := range s.rsvp {
		// a reservation ends with the connection to the relay; do not announce it then
		if s.host.Network().Connectedness(relayID) != network.Connected {
			continue
		}
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
	if nonce != "" {
		// YaCy puts the relay addresses into its seed: they are signed too, so that a process in between cannot swap them
		sig, err := s.priv.Sign([]byte(statusMessage(nonce, st.PeerID, st.Boot, st.Reachability, st.RelayAddrs)))
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		st.Sig = base64.RawURLEncoding.EncodeToString(sig)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(st)
}

// statusMessage is what the sidecar signs for YaCy; YaCy verifies it with the public key of its own peer key
func statusMessage(nonce, peerID, boot, reachability string, relayAddrs []string) string {
	return "yacy-sidecar-status-v2|" + nonce + "|" + peerID + "|" + boot + "|" + reachability + "|" + strings.Join(relayAddrs, " ")
}

// tunnelMessage is what the sidecar signs in a /tunnel answer: YaCy only uses a port that its own sidecar gave
func tunnelMessage(nonce, peerID string, port int, boot string) string {
	return "yacy-sidecar-tunnel-v1|" + nonce + "|" + peerID + "|" + strconv.Itoa(port) + "|" + boot
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
	sig, err := s.priv.Sign([]byte(tunnelMessage(r.URL.Query().Get("nonce"), id.String(), port, s.boot)))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"port": port, "boot": s.boot, "sig": base64.RawURLEncoding.EncodeToString(sig)})
}

// addPeerAddrs adds circuit addresses announced in a seed: only relay circuits that end in the peer's own id
func (s *sidecar) addPeerAddrs(id peer.ID, list string) {
	relays := map[peer.ID]peer.AddrInfo{}
	for _, r := range s.currentRelays() {
		relays[r.ID] = r
	}
	for _, info := range circuitAddrs(id, list, relays) {
		s.host.Peerstore().AddAddrs(id, info.Addrs, peerstore.TempAddrTTL)
	}
}

// circuitAddrs takes at most 8 '|' separated circuit addresses of the peer and keeps only the relay id of each: the
// addresses are rebuilt from the configured relays' own addresses. A seed (or a peer lying in it) chooses neither the
// host nor the port this sidecar dials; circuits through relays we do not know are dropped.
func circuitAddrs(id peer.ID, list string, relays map[peer.ID]peer.AddrInfo) []peer.AddrInfo {
	var out []peer.AddrInfo
	seen := map[peer.ID]bool{}
	for n, a := range strings.Split(list, "|") {
		a = strings.TrimSpace(a)
		if a == "" || n >= 8 {
			continue
		}
		idx := strings.Index(a, "/p2p-circuit/")
		if idx < 0 {
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
		relayPart, err := ma.NewMultiaddr(a[:idx])
		if err != nil {
			continue
		}
		claimed, err := peer.AddrInfoFromP2pAddr(relayPart)
		if err != nil {
			continue
		}
		known, ok := relays[claimed.ID]
		if !ok || seen[claimed.ID] {
			continue
		}
		seen[claimed.ID] = true
		var addrs []ma.Multiaddr
		for _, ra := range known.Addrs {
			circuit, err := ma.NewMultiaddr(ra.String() + "/p2p/" + known.ID.String() + "/p2p-circuit")
			if err == nil {
				addrs = append(addrs, circuit)
			}
		}
		if len(addrs) > 0 {
			out = append(out, peer.AddrInfo{ID: id, Addrs: addrs})
		}
	}
	return out
}

func (s *sidecar) tunnel(id peer.ID) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if t, ok := s.tunnels[id]; ok && t.retired.IsZero() {
		t.lastUsed = time.Now()
		return t.port, nil
	}
	if s.activeTunnelsLocked() >= maxTunnels {
		// a retired port stays closed to reuse until YaCy has surely forgotten it; with too many, refuse new tunnels
		// instead of freeing a port that YaCy may still send another peer's traffic to
		if len(s.retired) >= maxRetired {
			return 0, errors.New("too many tunnels, try again later")
		}
		s.retireOldestLocked()
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := l.Addr().(*net.TCPAddr).Port
	entry := &tunnelEntry{port: port, listener: l, lastUsed: time.Now()}
	if old, ok := s.tunnels[id]; ok {
		s.retireLocked(old) // a retired tunnel of this peer keeps its port until it expires
	}
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
		if res.ContentLength > maxResponseBody {
			return errors.New("answer too large")
		}
		res.Body = &limitedBody{ReadCloser: res.Body, remaining: maxResponseBody}
		return nil
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, err error) {
		log.Printf("tunnel to %s: %v", id, err)
		http.Error(w, "peer not reachable: "+err.Error(), http.StatusBadGateway)
	}
	portStr := fmt.Sprint(port)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// only YaCy uses the tunnel: no web pages (DNS rebinding, no-cors requests), and the Host must be the
		// loopback address. YaCy's clients send none of the browser headers.
		if !isLoopbackHost(r.Host, portStr) || isBrowserRequest(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		s.mu.Lock()
		retired := !entry.retired.IsZero()
		entry.lastUsed = time.Now()
		s.mu.Unlock()
		if retired {
			http.Error(w, "tunnel retired, ask /tunnel again", http.StatusGone)
			return
		}
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

func (s *sidecar) activeTunnelsLocked() int {
	return len(s.tunnels)
}

// retireOldestLocked retires the least recently used tunnel: it keeps its port and answers 410 until it expires
func (s *sidecar) retireOldestLocked() {
	ids := make([]peer.ID, 0, len(s.tunnels))
	for id := range s.tunnels {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return s.tunnels[ids[i]].lastUsed.Before(s.tunnels[ids[j]].lastUsed) })
	if len(ids) > 0 {
		t := s.tunnels[ids[0]]
		delete(s.tunnels, ids[0])
		s.retireLocked(t)
	}
}

// retireLocked keeps a tunnel's port for tunnelRetire (it answers 410), longer than YaCy remembers a port (5
// minutes). New tunnels are refused while maxRetired are waiting, so that file descriptors stay bounded without
// handing a port to another peer early.
func (s *sidecar) retireLocked(t *tunnelEntry) {
	if !t.retired.IsZero() {
		return
	}
	t.retired = time.Now()
	s.retired = append(s.retired, t)
}

// expireTunnels retires tunnels that were not used for a while and closes retired ones after tunnelRetire
func (s *sidecar) expireTunnels() {
	for {
		time.Sleep(time.Minute)
		s.mu.Lock()
		for id, t := range s.tunnels {
			if time.Since(t.lastUsed) > tunnelIdle {
				delete(s.tunnels, id)
				s.retireLocked(t)
			}
		}
		kept := s.retired[:0]
		for _, t := range s.retired {
			if t.retired.IsZero() {
				t.retired = time.Now()
			}
			if time.Since(t.retired) > tunnelRetire {
				t.listener.Close()
			} else {
				kept = append(kept, t)
			}
		}
		s.retired = kept
		s.mu.Unlock()
	}
}

// isBrowserRequest: requests of web pages carry Origin, Referer or Sec-Fetch-* headers; YaCy's clients do not
func isBrowserRequest(r *http.Request) bool {
	if r.Header.Get("Origin") != "" || r.Header.Get("Referer") != "" {
		return true
	}
	for name := range r.Header {
		if strings.HasPrefix(strings.ToLower(name), "sec-fetch-") {
			return true
		}
	}
	return false
}

// limitedBody ends an answer of a remote peer after maxResponseBody bytes
type limitedBody struct {
	io.ReadCloser
	remaining int64
}

func (b *limitedBody) Read(p []byte) (int, error) {
	if b.remaining < 0 {
		return 0, errors.New("answer too large")
	}
	// read one byte more than allowed, to tell the end of an answer of exactly the limit from a longer one
	if int64(len(p)) > b.remaining+1 {
		p = p[:b.remaining+1]
	}
	n, err := b.ReadCloser.Read(p)
	b.remaining -= int64(n)
	if b.remaining < 0 {
		return n + int(b.remaining), errors.New("answer too large")
	}
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
	c, err := gostream.Dial(network.WithAllowLimitedConn(ctx, "yacy"), s.host, id, yacyProtocol)
	if err != nil && s.hasLimitedConn(id) {
		// a relayed connection that reached the relay's limit is reset; close it and dial a new circuit
		_ = s.host.Network().ClosePeer(id)
		if err2 := s.host.Connect(network.WithAllowLimitedConn(ctx, "yacy"), peer.AddrInfo{ID: id}); err2 != nil {
			return nil, err
		}
		return gostream.Dial(network.WithAllowLimitedConn(ctx, "yacy"), s.host, id, yacyProtocol)
	}
	return c, err
}

func (s *sidecar) hasLimitedConn(id peer.ID) bool {
	for _, c := range s.host.Network().ConnsToPeer(id) {
		if c.Stat().Limited {
			return true
		}
	}
	return false
}

// serveStreams passes HTTP requests from other peers to the local YaCy
func (s *sidecar) serveStreams() {
	l, err := gostream.Listen(s.host, yacyProtocol)
	if err != nil {
		log.Fatalf("stream listener: %v", err)
	}
	srv := &http.Server{
		Handler:           s.limitPerPeer(yacyHandler(s.yacy, s.token)),
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
		if s.streams[r.RemoteAddr] >= perPeerStreams || s.total >= totalStreams {
			s.streamMu.Unlock()
			http.Error(w, "too many concurrent requests", http.StatusTooManyRequests)
			return
		}
		s.streams[r.RemoteAddr]++
		s.total++
		s.streamMu.Unlock()
		defer func() {
			s.streamMu.Lock()
			s.total--
			if s.streams[r.RemoteAddr]--; s.streams[r.RemoteAddr] <= 0 {
				delete(s.streams, r.RemoteAddr)
			}
			s.streamMu.Unlock()
		}()
		next.ServeHTTP(w, r)
	})
}

// yacyHandler passes requests from other peers to the sidecar connector of the local YaCy: only the peer-to-peer
// paths, with a size limit, without any header that YaCy could take as the client address, and with the libp2p
// peer id of the sender and the token that proves to YaCy that the sidecar set it.
func yacyHandler(yacy *url.URL, token string) http.Handler {
	proxy := &httputil.ReverseProxy{
		// Rewrite (unlike Director) runs after the hop-by-hop headers were removed, so a request cannot remove the
		// headers set here by naming them in its Connection header
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(yacy)
			pr.Out.Host = yacy.Host
			for _, h := range []string{"X-Real-Ip", "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "Client-Ip", "True-Client-Ip", "X-Yacy-Sidecar-Token", "X-Yacy-Libp2p-Peer"} {
				pr.Out.Header.Del(h)
			}
			// the remote address of a stream is the libp2p peer id, authenticated by the libp2p handshake
			pr.Out.Header.Set("X-YaCy-Libp2p-Peer", pr.In.RemoteAddr)
			if token != "" {
				pr.Out.Header.Set("X-YaCy-Sidecar-Token", token)
			}
		},
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowedPath.MatchString(r.URL.Path) || strings.Contains(r.URL.Path, "_p.") {
			http.Error(w, "not available through the sidecar", http.StatusForbidden)
			return
		}
		limit := int64(smallRequestBody)
		if r.URL.Path == "/yacy/transferRWI.html" || r.URL.Path == "/yacy/transferURL.html" {
			limit = maxRequestBody // index transfers
		}
		r.Body = http.MaxBytesReader(w, r.Body, limit)
		proxy.ServeHTTP(w, r)
	})
}
