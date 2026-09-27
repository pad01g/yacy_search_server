package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/libp2p/go-libp2p/core/peer"
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
	h := yacyHandler(u)

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

	denied := httptest.NewRecorder()
	h.ServeHTTP(denied, httptest.NewRequest(http.MethodGet, "http://peer/ConfigAccounts_p.html", nil))
	if denied.Code != http.StatusForbidden {
		t.Errorf("admin page passed with %d", denied.Code)
	}
}

func TestGuardProtectsControlAPI(t *testing.T) {
	s := &sidecar{token: "0123456789abcdef-token"}
	h := s.guard("127.0.0.1:8095", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
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
		req := httptest.NewRequest(http.MethodGet, "http://"+c.host+"/status", nil)
		req.Host = c.host
		if c.token != "" {
			req.Header.Set("X-YaCy-Sidecar-Token", c.token)
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
	got := circuitAddrs(id, good+"|"+other+"|"+direct+"|garbage")
	if len(got) != 1 || got[0].ID != id {
		t.Fatalf("expected only the circuit address of the peer, got %v", got)
	}
}

func TestLimitedBody(t *testing.T) {
	b := &limitedBody{ReadCloser: io.NopCloser(strings.NewReader(strings.Repeat("x", 100))), remaining: 10}
	data, err := io.ReadAll(b)
	if len(data) != 10 || err == nil {
		t.Fatalf("expected 10 bytes and an error, got %d, %v", len(data), err)
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
