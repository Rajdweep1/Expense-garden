package httpapi

import (
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
