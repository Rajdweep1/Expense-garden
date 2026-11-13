package httpapi

import (
	"context"
	"crypto/subtle"
	"net/http"
	"strings"
)

// requireToken guards every route but the two health checks.
//
// subtle.ConstantTimeCompare rather than ==: string comparison short-circuits on the first
// differing byte, which leaks the token's prefix to anyone who can time responses. One user
// makes that unlikely to matter and free to prevent.
//
// An unset server token fails closed. Config refuses to start without one, but the check that
// guards the whole ledger should not lean on that: ConstantTimeCompare of two empty slices is 1,
// which would make "Bearer " a key.
func (s *Server) requireToken(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token := strings.TrimPrefix(header, "Bearer ")
		if s.Token == "" || token == header || subtle.ConstantTimeCompare([]byte(token), []byte(s.Token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

// userIDFrom is the authenticated caller's user id.
func userIDFrom(ctx context.Context) int64 {
	return 0 // not implemented: Task 2 sets it in auth
}

// TokenLookup resolves a bearer token to a user. The store implements it; tests use a fake.
type TokenLookup interface {
	UserByToken(ctx context.Context, token string) (int64, bool, error)
}

func (s *Server) requireUser(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "not implemented"})
	}
}
