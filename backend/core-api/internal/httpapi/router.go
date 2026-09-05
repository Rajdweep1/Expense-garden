// Package httpapi is the HTTP surface: three routes, one of which needs no auth.
package httpapi

import (
	"context"
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
	// Liveness: is the process up? Deliberately does NOT touch the database. A platform that
	// restarts the container when Postgres blips would turn a recoverable outage into a crash
	// loop, and the phone already treats an unreachable server as "retry next signal".
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	// Readiness: can we actually serve? This one pings the database, because a server that
	// accepts pushes it cannot store is worse than one that refuses them — the phone would
	// advance its cursor past rows that never landed.
	mux.HandleFunc("GET /v1/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		if err := s.Store.Pool.Ping(ctx); err != nil {
			log.Printf("readiness: db unreachable: %v", err)
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db unreachable"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ready": true})
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
