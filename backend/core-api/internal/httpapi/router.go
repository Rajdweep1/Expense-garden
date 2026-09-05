// Package httpapi is the HTTP surface: three routes, one of which needs no auth.
package httpapi

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

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
	return logRequests(mux)
}

// logRequests exists because the phone turns every failure into silence (spec §5). Without a
// line per request there is no way to tell "the client never called" from "the call was
// rejected" — a distinction that cost an hour during the first device bring-up.
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(rec, r)
		log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, rec.status, time.Since(start).Round(time.Millisecond))
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
