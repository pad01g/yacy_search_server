package main

import (
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"
)

func TestAllowedPath(t *testing.T) {
	allowed := []string{"/yacy/hello.html", "/yacy/search.html", "/yacy/query.html", "/yacy/trust.json", "/yacy/transferRWI.html", "/solr/select", "/solr/collection1/select"}
	denied := []string{"/", "/ConfigProperties_p.html", "/yacy/../ConfigAccounts_p.html", "/solr/update", "/solr/select/../update", "/yacysearch.json", "/yacy/", "/Crawler_p.html", "/yacy/x/y.html", "/api/status_p.xml"}
	for _, p := range allowed {
		if !allowedPath.MatchString(p) {
			t.Errorf("%s should be allowed", p)
		}
	}
	for _, p := range denied {
		if allowedPath.MatchString(p) {
			t.Errorf("%s should be denied", p)
		}
	}
}

func TestYacyHandlerStripsClientAddressHeaders(t *testing.T) {
	var got http.Header
	var gotPath string
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()
	u, _ := url.Parse(backend.URL)
	h := yacyHandler(u, "secret-token-0123456789")

	req := httptest.NewRequest(http.MethodGet, "http://peer/yacy/hello.html", nil)
	req.RemoteAddr = "12D3KooWExample"
	req.Header.Set("X-Real-IP", "203.0.113.9")
	req.Header.Set("X-Forwarded-For", "203.0.113.9")
	req.Header.Set("Forwarded", "for=203.0.113.9")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || gotPath != "/yacy/hello.html" {
		t.Fatalf("request not passed: %d %s", rec.Code, gotPath)
	}
	for _, name := range []string{"X-Real-Ip", "X-Forwarded-For", "Forwarded"} {
		if v := got.Get(name); v != "" {
			t.Errorf("%s reached YaCy: %s", name, v)
		}
	}
	if got.Get("X-YaCy-Libp2p-Peer") != "12D3KooWExample" {
		t.Errorf("peer header missing: %v", got)
	}
	if got.Get("X-YaCy-Sidecar-Token") != "secret-token-0123456789" {
		t.Errorf("token not passed to YaCy: %v", got)
	}

	// a request cannot remove the peer header by naming it in Connection, nor set its own
	req2 := httptest.NewRequest(http.MethodGet, "http://peer/yacy/hello.html", nil)
	req2.RemoteAddr = "12D3KooWExample"
	req2.Header.Set("Connection", "X-YaCy-Libp2p-Peer, X-YaCy-Sidecar-Token")
	req2.Header.Set("X-YaCy-Sidecar-Token", "forged")
	h.ServeHTTP(httptest.NewRecorder(), req2)
	if got.Get("X-YaCy-Libp2p-Peer") != "12D3KooWExample" || got.Get("X-YaCy-Sidecar-Token") != "secret-token-0123456789" {
		t.Errorf("headers could be removed or forged: %v", got)
	}

	denied := httptest.NewRecorder()
	h.ServeHTTP(denied, httptest.NewRequest(http.MethodGet, "http://peer/ConfigAccounts_p.html", nil))
	if denied.Code != http.StatusForbidden {
		t.Errorf("admin page passed with %d", denied.Code)
	}
}

func TestGuardProtectsControlAPI(t *testing.T) {
	s := &sidecar{token: "0123456789abcdef-token"}
	h := s.guard("127.0.0.1:8095", true, func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	cases := []struct {
		name, host, token, origin string
		want                      int
	}{
		{"ok", "127.0.0.1:8095", "0123456789abcdef-token", "", http.StatusOK},
		{"no token", "127.0.0.1:8095", "", "", http.StatusUnauthorized},
		{"wrong token", "127.0.0.1:8095", "x", "", http.StatusUnauthorized},
		{"dns rebinding host", "evil.example:8095", "0123456789abcdef-token", "", http.StatusForbidden},
		{"web page", "127.0.0.1:8095", "0123456789abcdef-token", "http://evil.example", http.StatusForbidden},
	}
	for _, c := range cases {
		nonce := strconv.FormatInt(time.Now().UnixMilli(), 10) + ".nonce-" + c.name
		req := httptest.NewRequest(http.MethodGet, "http://"+c.host+"/status?nonce="+url.QueryEscape(nonce), nil)
		req.Host = c.host
		if c.token != "" {
			req.Header.Set("X-YaCy-Sidecar-Auth", requestMAC(c.token, http.MethodGet, "/status", nonce))
		}
		if c.origin != "" {
			req.Header.Set("Origin", c.origin)
		}
		rec := httptest.NewRecorder()
		h(rec, req)
		if rec.Code != c.want {
			t.Errorf("%s: got %d, want %d", c.name, rec.Code, c.want)
		}
	}
}

func TestCircuitAddrsOnlyForThePeer(t *testing.T) {
	const self = "12D3KooWPfWXgpH4oe3T25mCsdrHArxB6C2KxnB48wJNhx5jpMbo"
	const relayID = "12D3KooWD8JgJycvxctGLLYyz9z4zAqbTT6DmumtmhgdGUKpMw8R"
	id, err := peer.Decode(self)
	if err != nil {
		t.Fatal(err)
	}
	good := "/ip4/172.30.0.3/tcp/4001/p2p/" + relayID + "/p2p-circuit/p2p/" + self
	other := "/ip4/172.30.0.3/tcp/4001/p2p/" + relayID + "/p2p-circuit/p2p/" + relayID
	direct := "/ip4/10.0.0.1/tcp/4001/p2p/" + self
	relayPeer, _ := peer.Decode(relayID)
	relayAddr, _ := ma.NewMultiaddr("/ip4/172.30.0.3/tcp/4001")
	known := map[peer.ID]peer.AddrInfo{relayPeer: {ID: relayPeer, Addrs: []ma.Multiaddr{relayAddr}}}
	got := circuitAddrs(id, good+"|"+other+"|"+direct+"|garbage", known)
	if len(got) != 1 || got[0].ID != id {
		t.Fatalf("expected only the circuit address of the peer, got %v", got)
	}
	// a circuit through a relay we do not know (e.g. an internal host) is dropped
	if got := circuitAddrs(id, good, map[peer.ID]peer.AddrInfo{}); len(got) != 0 {
		t.Fatalf("circuit through an unknown relay accepted: %v", got)
	}
	// a known relay id with a hostile transport part: the address is rebuilt from the relay's own address
	hostile := "/ip4/169.254.169.254/tcp/80/p2p/" + relayID + "/p2p-circuit/p2p/" + self
	got = circuitAddrs(id, hostile, known)
	if len(got) != 1 || len(got[0].Addrs) != 1 || !strings.HasPrefix(got[0].Addrs[0].String(), "/ip4/172.30.0.3/tcp/4001/") {
		t.Fatalf("hostile transport address was not replaced: %v", got)
	}
}

func TestLimitedBody(t *testing.T) {
	b := &limitedBody{ReadCloser: io.NopCloser(strings.NewReader(strings.Repeat("x", 100))), remaining: 10}
	data, err := io.ReadAll(b)
	if len(data) != 10 || err == nil {
		t.Fatalf("expected 10 bytes and an error, got %d, %v", len(data), err)
	}
	exact := &limitedBody{ReadCloser: io.NopCloser(strings.NewReader(strings.Repeat("x", 10))), remaining: 10}
	data, err = io.ReadAll(exact)
	if len(data) != 10 || err != nil {
		t.Fatalf("an answer of exactly the limit must end normally, got %d, %v", len(data), err)
	}
}

func TestBrowserRequestsAreRecognized(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:1234/yacy/search.html", nil)
	if isBrowserRequest(r) {
		t.Fatal("a plain client request is not a browser request")
	}
	r.Header.Set("Sec-Fetch-Mode", "no-cors")
	if !isBrowserRequest(r) {
		t.Fatal("no-cors request of a web page not recognized")
	}
}

func TestLimitPerPeer(t *testing.T) {
	s := &sidecar{streams: map[string]int{}}
	release := make(chan struct{})
	started := make(chan struct{}, perPeerStreams)
	h := s.limitPerPeer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		started <- struct{}{}
		<-release
	}))
	for i := 0; i < perPeerStreams; i++ {
		go func() {
			req := httptest.NewRequest(http.MethodGet, "http://peer/yacy/search.html", nil)
			req.RemoteAddr = "peerA"
			h.ServeHTTP(httptest.NewRecorder(), req)
		}()
	}
	for i := 0; i < perPeerStreams; i++ {
		<-started
	}
	req := httptest.NewRequest(http.MethodGet, "http://peer/yacy/search.html", nil)
	req.RemoteAddr = "peerA"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("expected 429 for the extra request, got %d", rec.Code)
	}
	close(release)
}

func TestRetiredTunnelsKeepTheirPortsAndNewTunnelsWait(t *testing.T) {
	s := &sidecar{tunnels: map[peer.ID]*tunnelEntry{}}
	for i := 0; i < maxRetired; i++ {
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		s.retireLocked(&tunnelEntry{port: l.Addr().(*net.TCPAddr).Port, listener: l})
	}
	defer func() {
		for _, r := range s.retired {
			r.listener.Close()
		}
	}()
	// no listener was closed early
	for _, r := range s.retired {
		c, err := net.Dial("tcp", r.listener.Addr().String())
		if err != nil {
			t.Fatalf("a retired port was closed: %v", err)
		}
		c.Close()
	}
	// with all tunnel slots taken and the retired list full, a new tunnel is refused
	for i := 0; i < maxTunnels; i++ {
		s.tunnels[peer.ID(strings.Repeat("x", 1)+string(rune('a'+i%26))+string(rune(i)))] = &tunnelEntry{}
	}
	if _, err := s.tunnel(peer.ID("new")); err == nil {
		t.Fatal("a new tunnel was opened although the retired list is full")
	}
}

func signedRequest(token, path string, now time.Time) *http.Request {
	nonce := strconv.FormatInt(now.UnixMilli(), 10) + ".abcdefghijklmnop"
	r := httptest.NewRequest("GET", path+"?nonce="+nonce, nil)
	r.Header.Set("X-YaCy-Sidecar-Auth", requestMAC(token, "GET", r.URL.Path, nonce))
	return r
}

func TestControlRequestsNeedAFreshMAC(t *testing.T) {
	s := &sidecar{token: "0123456789abcdef0123"}
	now := time.Now()
	r := signedRequest(s.token, "/tunnel/12D3KooWPeer", now)
	if err := s.checkAuth(r, now); err != nil {
		t.Fatalf("a valid request was refused: %v", err)
	}
	if err := s.checkAuth(r, now); err == nil {
		t.Fatal("a replayed nonce was accepted")
	}
	if err := s.checkAuth(signedRequest("wrong-token-0123456789", "/tunnel/12D3KooWPeer", now), now); err == nil {
		t.Fatal("a MAC with another token was accepted")
	}
	if err := s.checkAuth(signedRequest(s.token, "/tunnel/12D3KooWPeer", now.Add(-10*time.Minute)), now); err == nil {
		t.Fatal("an old nonce was accepted")
	}
	// the raw token is not enough any more
	raw := httptest.NewRequest("GET", "/tunnel/12D3KooWPeer", nil)
	raw.Header.Set("X-YaCy-Sidecar-Token", s.token)
	if err := s.checkAuth(raw, now); err == nil {
		t.Fatal("the bearer token was accepted")
	}
	// the MAC covers the path
	moved := signedRequest(s.token, "/tunnel/12D3KooWPeer", now.Add(time.Second))
	moved.URL.Path = "/tunnel/12D3KooWOther"
	if err := s.checkAuth(moved, now); err == nil {
		t.Fatal("a MAC for another path was accepted")
	}
}

func TestRequestMACMatchesYaCy(t *testing.T) {
	// the same vector is in P2PRouteTest.java
	got := requestMAC("0123456789abcdef0123", "GET", "/tunnel/12D3KooWPeer", "1700000000000.abc")
	if got != "pnT__vOqTRzq_o8vvskl9iOlV_ARlbHXKz4FaLD9br4" {
		t.Fatalf("MAC %s", got)
	}
}

func TestStatusIsSignedWithThePeerKey(t *testing.T) {
	priv, pub, err := crypto.GenerateEd25519Key(nil)
	if err != nil {
		t.Fatal(err)
	}
	relays := []string{"/ip4/1.2.3.4/tcp/4001/p2p/R/p2p-circuit/p2p/P"}
	msg := statusMessage("n0nce", "12D3KooWPeer", "boot1", "private", relays)
	sig, err := priv.Sign([]byte(msg))
	if err != nil {
		t.Fatal(err)
	}
	ok, err := pub.Verify([]byte(msg), sig)
	if err != nil || !ok {
		t.Fatalf("signature does not verify: %v", err)
	}
	if statusMessage("other", "12D3KooWPeer", "boot1", "private", relays) == msg {
		t.Fatal("the nonce is not part of the message")
	}
	if statusMessage("n0nce", "12D3KooWPeer", "boot1", "private", []string{"/ip4/6.6.6.6/tcp/1/p2p/X/p2p-circuit/p2p/P"}) == msg {
		t.Fatal("the relay addresses are not part of the message")
	}
	if tunnelMessage("n", "12D3KooWPeer", 4000, "b") == tunnelMessage("n", "12D3KooWPeer", 4001, "b") {
		t.Fatal("the port is not part of the tunnel message")
	}
}
