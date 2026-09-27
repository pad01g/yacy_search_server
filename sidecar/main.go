// yacy-sidecar: NAT traversal for YaCy peers with go-libp2p (see docs/trust-and-nat.md, section 6).
//
// The sidecar runs next to a YaCy peer and uses the same Ed25519 key, so its libp2p peer id is derived from the
// key of the YaCy seed. It
//   - accepts libp2p streams (protocol /yacy/http/1.0.0) and passes the HTTP requests in them to the local YaCy,
//     restricted to the peer-to-peer endpoints (/yacy/..., Solr select);
//   - opens a local tunnel port per remote peer on request (GET /tunnel/<peer id>); HTTP sent to the tunnel is
//     carried to that peer, through a circuit relay when the peer is behind a NAT;
//   - reports its peer id, reachability (AutoNAT) and relay addresses on GET /status;
//   - with -relay-service, acts as a circuit relay v2 for others.
package main

import (
	"context"
	"crypto/ed25519"
	"crypto/x509"
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

// maxTunnels bounds the number of local tunnel ports (one per remote peer)
const maxTunnels = 1024

// maxRequestBody bounds requests carried to the local YaCy (index transfers are the largest)
const maxRequestBody = 32 << 20

// allowedPath: only the peer-to-peer interface of YaCy is reachable through the sidecar, never the admin pages
var allowedPath = regexp.MustCompile(`^/(yacy/[A-Za-z0-9_]+\.(html|json|xml)|solr/([a-z0-9_]+/)?select)$`)

type sidecar struct {
	host    host.Host
	yacy    *url.URL
	relays  []peer.AddrInfo
	mu      sync.Mutex
	tunnels map[peer.ID]int
	reach   network.Reachability
	reachMu sync.RWMutex
	// relay reservations made by reserveLoop: relay -> circuit addresses of this peer
	rsvpMu sync.Mutex
	rsvp   map[peer.ID][]string
}

func main() {
	keyFile := flag.String("key", "", "PEM (PKCS#8) Ed25519 key of the YaCy peer; empty: random key (relay only)")
	listen := flag.String("listen", "/ip4/0.0.0.0/tcp/4001", "libp2p listen addresses, comma separated")
	httpAddr := flag.String("http", "127.0.0.1:8095", "address of the local control API (/status, /tunnel/<id>)")
	yacyURL := flag.String("yacy", "http://127.0.0.1:8090", "base URL of the local YaCy")
	relaysFlag := flag.String("relay", "", "relays, comma separated: multiaddrs with /p2p/<id>, or http://host:port of a relay sidecar's control API")
	relayService := flag.Bool("relay-service", false, "act as a circuit relay for other peers")
	reachability := flag.String("reachability", "auto", "auto, public or private (override AutoNAT)")
	keyWait := flag.Duration("key-wait", 5*time.Minute, "how long to wait for the key file to appear")
	flag.Parse()

	priv, err := loadKey(*keyFile, *keyWait)
	if err != nil {
		log.Fatalf("key: %v", err)
	}
	yacy, err := url.Parse(*yacyURL)
	if err != nil {
		log.Fatalf("yacy url: %v", err)
	}
	relays := resolveRelays(*relaysFlag)

	opts := []libp2p.Option{
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(strings.Split(*listen, ",")...),
		libp2p.EnableRelay(),
		libp2p.EnableHolePunching(),
		libp2p.EnableNATService(),
	}
	if len(relays) > 0 {
		opts = append(opts, libp2p.EnableAutoRelayWithStaticRelays(relays))
	}
	if *relayService {
		res := relay.DefaultResources()
		// the default limit (2 minutes, 128 KiB per circuit) is too small for search answers and index transfers
		res.Limit = &relay.RelayLimit{Duration: 30 * time.Minute, Data: 64 << 20}
		opts = append(opts, libp2p.EnableRelayService(relay.WithResources(res)))
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
	s := &sidecar{host: h, yacy: yacy, relays: relays, tunnels: map[peer.ID]int{}, reach: network.ReachabilityUnknown, rsvp: map[peer.ID][]string{}}
	switch *reachability {
	case "public":
		s.reach = network.ReachabilityPublic
	case "private":
		s.reach = network.ReachabilityPrivate
	}
	log.Printf("peer id %s, listening on %v, relays %v, relay service %v", h.ID(), h.Addrs(), relays, *relayService)

	go s.watchReachability()
	for _, r := range relays {
		go s.keepConnected(r)
		if !*relayService {
			go s.reserveLoop(r)
		}
	}
	go s.serveStreams()

	mux := http.NewServeMux()
	mux.HandleFunc("/status", s.handleStatus)
	mux.HandleFunc("/tunnel/", s.handleTunnel)
	log.Fatal((&http.Server{Addr: *httpAddr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}).ListenAndServe())
}

// loadKey reads the YaCy peer key, waiting for YaCy to create it on its first start
func loadKey(file string, wait time.Duration) (crypto.PrivKey, error) {
	if file == "" {
		priv, _, err := crypto.GenerateEd25519Key(nil)
		return priv, err
	}
	deadline := time.Now().Add(wait)
	for {
		data, err := os.ReadFile(file)
		if err == nil {
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
		if time.Now().After(deadline) {
			return nil, err
		}
		time.Sleep(2 * time.Second)
	}
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
			for i := 0; ; i++ {
				info, err := relayFromStatus(r)
				if err == nil {
					out = append(out, *info)
					break
				}
				if i%10 == 0 {
					log.Printf("waiting for relay %s: %v", r, err)
				}
				time.Sleep(2 * time.Second)
			}
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

// keepConnected keeps a connection to a relay, which AutoNAT and AutoRelay need
func (s *sidecar) keepConnected(r peer.AddrInfo) {
	for {
		if s.host.Network().Connectedness(r.ID) != network.Connected {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			if err := s.host.Connect(ctx, r); err != nil {
				log.Printf("relay %s: %v", r.ID, err)
			}
			cancel()
		}
		time.Sleep(15 * time.Second)
	}
}

func (s *sidecar) reachability() network.Reachability {
	s.reachMu.RLock()
	defer s.reachMu.RUnlock()
	return s.reach
}

// reserveLoop keeps a reservation on the relay while this peer is not known to be publicly reachable.
// AutoRelay only reserves after AutoNAT reported "private", and AutoNAT cannot decide as long as the peer only
// has private addresses (or needs several minutes); YaCy decides from its own hello results whether it uses the
// relay addresses.
func (s *sidecar) reserveLoop(r peer.AddrInfo) {
	for {
		if s.reachability() == network.ReachabilityPublic {
			s.rsvpMu.Lock()
			delete(s.rsvp, r.ID)
			s.rsvpMu.Unlock()
			time.Sleep(30 * time.Second)
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		res, err := client.Reserve(ctx, s.host, r)
		cancel()
		if err != nil {
			log.Printf("reservation on relay %s: %v", r.ID, err)
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
			addrs = append(addrs, a.String()+"/p2p/"+r.ID.String()+"/p2p-circuit/p2p/"+s.host.ID().String())
		}
		s.rsvpMu.Lock()
		first := len(s.rsvp[r.ID]) == 0
		s.rsvp[r.ID] = addrs
		s.rsvpMu.Unlock()
		if first {
			log.Printf("reserved a slot on relay %s until %s: %v", r.ID, res.Expiration.Format(time.RFC3339), addrs)
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

func (s *sidecar) handleStatus(w http.ResponseWriter, _ *http.Request) {
	st := status{PeerID: s.host.ID().String(), Reachability: strings.ToLower(s.reachability().String())}
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
	for _, r := range s.relays {
		st.Relays = append(st.Relays, r.ID.String())
	}
	s.mu.Lock()
	st.Tunnels = len(s.tunnels)
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(st)
}

// handleTunnel returns the local port of the tunnel to a peer, opening it on first use
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
	port, err := s.tunnel(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, "{\"port\":%d}\n", port)
}

func (s *sidecar) tunnel(id peer.ID) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p, ok := s.tunnels[id]; ok {
		return p, nil
	}
	if len(s.tunnels) >= maxTunnels {
		return 0, errors.New("too many tunnels")
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := l.Addr().(*net.TCPAddr).Port
	s.tunnels[id] = port
	target := &url.URL{Scheme: "http", Host: "peer"}
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.Transport = &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return s.dial(ctx, id)
		},
		DisableKeepAlives:     true,
		ResponseHeaderTimeout: 30 * time.Second,
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, err error) {
		log.Printf("tunnel to %s: %v", id, err)
		http.Error(w, "peer not reachable: "+err.Error(), http.StatusBadGateway)
	}
	go func() {
		srv := &http.Server{Handler: proxy, ReadHeaderTimeout: 30 * time.Second}
		log.Printf("tunnel to %s on 127.0.0.1:%d", id, port)
		if err := srv.Serve(l); err != nil {
			log.Printf("tunnel to %s closed: %v", id, err)
		}
	}()
	return port, nil
}

// dial opens a stream to the peer, directly if we know an address, otherwise through the relays
func (s *sidecar) dial(ctx context.Context, id peer.ID) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	if s.host.Network().Connectedness(id) != network.Connected {
		for _, r := range s.relays {
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
	handler := yacyHandler(s.yacy)
	srv := &http.Server{Handler: handler, ReadHeaderTimeout: 30 * time.Second, WriteTimeout: 60 * time.Second}
	log.Fatal(srv.Serve(l))
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
		if !allowedPath.MatchString(r.URL.Path) {
			http.Error(w, "not available through the sidecar", http.StatusForbidden)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxRequestBody)
		// the remote address of a stream is the libp2p peer id; YaCy sees the request as local
		r.Header.Set("X-YaCy-Libp2p-Peer", r.RemoteAddr)
		proxy.ServeHTTP(w, r)
	})
}
