package httpapi

import (
	"context"
	"log"
	"net/http"
	"strings"
)

// TokenLookup resolves a bearer token to a user. The store implements it; tests use a fake.
type TokenLookup interface {
	UserByToken(ctx context.Context, token string) (int64, bool, error)
}

type ctxKey struct{}

// requireUser guards every route but the health checks. It resolves the bearer token to a user
// and hands that user's id to the handler; every query below is scoped by it.
//
// Lookup is by the token's SHA-256 through a unique index, so there is no byte-by-byte compare
// to time. The token is trimmed because the phone trims what is pasted (SyncPrefs.token).
func (s *Server) requireUser(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
		if token == "" || !strings.HasPrefix(header, "Bearer ") {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		id, ok, err := s.Users.UserByToken(r.Context(), token)
		if err != nil {
			// Not a 401: the phone reads 401 as "token rejected", and a database blip is not that.
			log.Printf("auth: user lookup failed: %v", err)
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "auth failed"})
			return
		}
		if !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
	}
}

// userIDFrom is the authenticated caller's user id. Only handlers behind requireUser call it.
func userIDFrom(ctx context.Context) int64 {
	return ctx.Value(ctxKey{}).(int64)
}
