package httpapi

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The shape `openssl rand -base64 32` produces: 44 characters.
const goodToken = "q5Zx2vS8nB1fK7mW3tJ9hL0cR6yA4uE8pG2dV5oN1iM="

const goodUser int64 = 42

// fakeUsers knows one token. It stands in for the store, so these tests run without a database.
type fakeUsers struct{ err error }

func (f fakeUsers) UserByToken(_ context.Context, token string) (int64, bool, error) {
	if f.err != nil {
		return 0, false, f.err
	}
	if token == goodToken {
		return goodUser, true, nil
	}
	return 0, false, nil
}

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
	h := (&Server{Users: fakeUsers{}}).Routes()
	creds := []struct{ name, header string }{ // an empty header means none is sent
		{"no header", ""},
		{"wrong token", "Bearer " + strings.Repeat("x", len(goodToken))},
		{"token without the scheme", goodToken},
		{"empty bearer", "Bearer "},
		{"blank bearer", "Bearer    "},
		{"basic scheme", "Basic " + goodToken},
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

// AC-12: the phone trims what is pasted; the server trims what it is sent. Either side's stray
// whitespace must not turn a good token into a rejected one.
func TestSurroundingWhitespaceStillAuthenticates(t *testing.T) {
	h := (&Server{Users: fakeUsers{}}).Routes()
	for _, header := range []string{"Bearer " + goodToken + " ", "Bearer  " + goodToken, "Bearer " + goodToken + "\n"} {
		req := httptest.NewRequest(http.MethodPost, "/v1/sync/push", strings.NewReader("not json"))
		req.Header.Set("Authorization", header)
		// 400 "malformed body" is the push handler's own answer: getting it proves auth passed,
		// without a database behind the handler.
		if rec := serve(t, h, req); rec.Code != http.StatusBadRequest {
			t.Fatalf("%q: want 400 from the push handler, got %d", header, rec.Code)
		}
	}
}

func TestTheRightTokenGetsThrough(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/v1/sync/push", strings.NewReader("not json"))
	req.Header.Set("Authorization", "Bearer "+goodToken)
	if rec := serve(t, (&Server{Users: fakeUsers{}}).Routes(), req); rec.Code != http.StatusBadRequest {
		t.Fatalf("want 400 from the push handler, got %d (%s)", rec.Code, strings.TrimSpace(rec.Body.String()))
	}
}

func TestTheHandlerSeesTheCallersUserID(t *testing.T) {
	var seen int64
	h := (&Server{Users: fakeUsers{}}).requireUser(func(w http.ResponseWriter, r *http.Request) {
		seen = userIDFrom(r.Context())
	})
	req := httptest.NewRequest(http.MethodGet, "/v1/sync/snapshot", nil)
	req.Header.Set("Authorization", "Bearer "+goodToken)
	h(httptest.NewRecorder(), req)
	if seen != goodUser {
		t.Fatalf("handler saw user %d, want %d", seen, goodUser)
	}
}

// A database outage must not read on the phone as "token rejected" (plan ruling R-P4).
func TestALookupErrorIs500NotUnauthorized(t *testing.T) {
	h := (&Server{Users: fakeUsers{err: errors.New("db down")}}).Routes()
	req := httptest.NewRequest(http.MethodGet, "/v1/sync/snapshot", nil)
	req.Header.Set("Authorization", "Bearer "+goodToken)
	if rec := serve(t, h, req); rec.Code != http.StatusInternalServerError {
		t.Fatalf("want 500, got %d", rec.Code)
	}
}

func TestHealthNeedsNoToken(t *testing.T) {
	rec := serve(t, (&Server{Users: fakeUsers{}}).Routes(), httptest.NewRequest(http.MethodGet, "/v1/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("want 200, got %d", rec.Code)
	}
}
