package httpapi

import (
	"encoding/json"
	"log"
	"net/http"

	"expensegarden/core-api/internal/store"
)

type pushRequest struct {
	Categories []store.Category  `json:"categories"`
	Payees     []store.Payee     `json:"payees"`
	Txns       []store.Txn       `json:"txns"`
	Budgets    []store.Budget    `json:"budgets"`
	Tombstones []store.Tombstone `json:"tombstones"`
	Events     []store.Event     `json:"events"`
}

// maxPushBytes: the first sync after install carries the entire history in one batch.
const maxPushBytes = 32 << 20

func (s *Server) handlePush(w http.ResponseWriter, r *http.Request) {
	var req pushRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxPushBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "malformed body"})
		return
	}
	batch := store.Batch{
		Categories: req.Categories, Payees: req.Payees, Txns: req.Txns,
		Budgets: req.Budgets, Tombstones: req.Tombstones, Events: req.Events,
	}
	if err := s.Store.ApplyBatch(r.Context(), batch); err != nil {
		// The phone treats any non-2xx as "cursors do not advance", so a failure here is
		// simply retried with the same rows. Nothing is lost by refusing — but the reason
		// must reach the server log, because it never reaches the phone.
		log.Printf("push rejected: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "apply failed"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{
		"accepted": len(batch.Categories) + len(batch.Payees) + len(batch.Txns) +
			len(batch.Budgets) + len(batch.Events),
	})
}
