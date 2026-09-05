package httpapi

import "net/http"

func (s *Server) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	b, err := s.Store.Snapshot(r.Context())
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "snapshot failed"})
		return
	}
	// Empty slices, not nulls: the client's readers iterate arrays, and a JSON null would be a
	// parse failure that reads on the phone as "restore did nothing".
	writeJSON(w, http.StatusOK, map[string]any{
		"categories": nonNil(b.Categories),
		"payees":     nonNil(b.Payees),
		"txns":       nonNil(b.Txns),
		"budgets":    nonNil(b.Budgets),
		"events":     nonNil(b.Events),
	})
}

func nonNil[T any](v []T) []T {
	if v == nil {
		return []T{}
	}
	return v
}
