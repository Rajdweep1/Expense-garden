// Package fcimport turns a Fortune City export into a user's history, exactly as recorded.
package fcimport

import (
	"context"
	"errors"
	"io"
	"time"

	"expensegarden/core-api/internal/store"
)

// Entry is one row of the export, as the scrape wrote it.
type Entry struct {
	Date       string `json:"date"`
	Category   string `json:"category"`
	Note       string `json:"note"`
	AmountINR  string `json:"amount_inr"`
	Type       string `json:"type"`
	Source     string `json:"source"`
	OrderInDay int    `json:"order_in_day"`
}

// CategoryTotal is one category's share of an import.
type CategoryTotal struct {
	Count int
	Paise int64
}

// Summary is what an import did.
type Summary struct {
	Inserted, Skipped int
	ByCategory        map[int64]CategoryTotal
}

var errNotImplemented = errors.New("not implemented")

func Parse(r io.Reader) ([]Entry, error) { return nil, errNotImplemented }
func Paise(s string) (int64, error)      { return 0, errNotImplemented }
func CategoryFor(e Entry) (int64, error) { return 0, errNotImplemented }
func Plan(entries []Entry, now int64, zone *time.Location) ([]store.Txn, error) {
	return nil, errNotImplemented
}
func Import(ctx context.Context, s *store.Store, userID int64, entries []Entry, now int64) (Summary, error) {
	return Summary{}, errNotImplemented
}
