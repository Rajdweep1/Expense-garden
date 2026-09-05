// Package httpapi is the HTTP surface: three routes, one of which needs no auth.
package httpapi

import (
	"encoding/json"
	"net/http"

	"expensegarden/core-api/internal/store"
)

type Server struct {
	Token string
	Store *store.Store
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	// Unauthenticated on purpose: an uptime check should not need a credential, and it
	// reveals nothing but liveness.
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /v1/sync/push", s.requireToken(s.handlePush))
	mux.HandleFunc("GET /v1/sync/snapshot", s.requireToken(s.handleSnapshot))
	return mux
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
