package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The shape `openssl rand -base64 32` produces (runbook §3): 44 characters.
const goodToken = "q5Zx2vS8nB1fK7mW3tJ9hL0cR6yA4uE8pG2dV5oN1iM="

var protected = []struct{ name, method, path string }{
	{"push", http.MethodPost, "/v1/sync/push"},
	{"snapshot", http.MethodGet, "/v1/sync/snapshot"},
}

// serve runs one request through the real router. Every server in this file has a nil Store,
// so a request that wrongly passes auth panics inside its handler; that is reported as what it
// is, rather than as a crash.
func serve(t *testing.T, h http.Handler, req *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	defer func() {
		if p := recover(); p != nil {
			t.Fatalf("%s %s reached its handler, so auth let it through (panic on the nil store: %v)",
				req.Method, req.URL.Path, p)
		}
	}()
	h.ServeHTTP(rec, req)
	return rec
}

func TestEveryWrongCredentialIsRefused(t *testing.T) {
	h := (&Server{Token: goodToken}).Routes()
	creds := []struct{ name, header string }{ // an empty header means none is sent
		{"no header", ""},
		{"wrong token", "Bearer " + strings.Repeat("x", len(goodToken))},
		{"token without the scheme", goodToken},
		{"empty bearer", "Bearer "},
		{"basic scheme", "Basic " + goodToken},
		{"token with a trailing space", "Bearer " + goodToken + " "},
		{"prefix of the token", "Bearer " + goodToken[:len(goodToken)-1]},
	}
	for _, r := range protected {
		t.Run(r.name, func(t *testing.T) {
			for _, c := range creds {
				t.Run(c.name, func(t *testing.T) {
					req := httptest.NewRequest(r.method, r.path, strings.NewReader("not json"))
					if c.header != "" {
						req.Header.Set("Authorization", c.header)
					}
					if rec := serve(t, h, req); rec.Code != http.StatusUnauthorized {
						t.Fatalf("want 401, got %d (%s)", rec.Code, strings.TrimSpace(rec.Body.String()))
					}
				})
			}
		})
	}
}

func TestTheRightTokenGetsThrough(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/v1/sync/push", strings.NewReader("not json"))
	req.Header.Set("Authorization", "Bearer "+goodToken)
	rec := serve(t, (&Server{Token: goodToken}).Routes(), req)
	// 400 "malformed body" is the push handler's own answer: getting it proves auth passed,
	// without a database behind the handler.
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("want 400 from the push handler, got %d (%s)", rec.Code, strings.TrimSpace(rec.Body.String()))
	}
}

func TestHealthNeedsNoToken(t *testing.T) {
	rec := serve(t, (&Server{Token: goodToken}).Routes(), httptest.NewRequest(http.MethodGet, "/v1/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("want 200, got %d", rec.Code)
	}
}

// Config refuses to start without a token, but the check guarding the whole ledger must not
// lean on that. ConstantTimeCompare of two empty slices is 1, so a server with no token
// accepted "Bearer " as a key.
func TestAnEmptyServerTokenAuthenticatesNothing(t *testing.T) {
	h := (&Server{Token: ""}).Routes()
	for _, r := range protected {
		t.Run(r.name, func(t *testing.T) {
			req := httptest.NewRequest(r.method, r.path, strings.NewReader("not json"))
			req.Header.Set("Authorization", "Bearer ")
			if rec := serve(t, h, req); rec.Code != http.StatusUnauthorized {
				t.Fatalf("want 401, got %d (%s)", rec.Code, strings.TrimSpace(rec.Body.String()))
			}
		})
	}
}
