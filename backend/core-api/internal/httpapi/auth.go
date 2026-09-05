package httpapi

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// requireToken guards every route but health.
//
// subtle.ConstantTimeCompare rather than ==: string comparison short-circuits on the first
// differing byte, which leaks the token's prefix to anyone who can time responses. One user
// makes that unlikely to matter and free to prevent.
func (s *Server) requireToken(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token := strings.TrimPrefix(header, "Bearer ")
		if token == header || subtle.ConstantTimeCompare([]byte(token), []byte(s.Token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}
