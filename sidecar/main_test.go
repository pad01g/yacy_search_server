package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
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
