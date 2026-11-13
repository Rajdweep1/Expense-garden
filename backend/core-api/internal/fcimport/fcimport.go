// Package fcimport turns a Fortune City export into a user's history, exactly as recorded:
// amount, note and date are FC's. The only changes are the category mapping he ruled on
// (spec rulings 4, 5 and 5a) and a time of day, which FC never stored.
package fcimport

import (
	"context"
	"crypto/sha1"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"

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

// Summary is what an import did. ByCategory covers every entry, inserted or skipped.
type Summary struct {
	Inserted, Skipped int
	ByCategory        map[int64]CategoryTotal
}

// Parse reads the export. Unknown fields and non-expense rows are refused: FC had no income in
// his ledger, and anything else means the file isn't the one this was written for.
func Parse(r io.Reader) ([]Entry, error) {
	dec := json.NewDecoder(r)
	dec.DisallowUnknownFields()
	var es []Entry
	if err := dec.Decode(&es); err != nil {
		return nil, fmt.Errorf("fcimport: parse: %w", err)
	}
	for i, e := range es {
		if e.Type != "expense" {
			return nil, fmt.Errorf("fcimport: row %d: type %q, want \"expense\"", i, e.Type)
		}
	}
	return es, nil
}

var amountRE = regexp.MustCompile(`^(\d+)(?:\.(\d{1,2}))?$`)

// Paise parses FC's decimal rupee string exactly; no float ever holds money.
func Paise(s string) (int64, error) {
	m := amountRE.FindStringSubmatch(s)
	if m == nil {
		return 0, fmt.Errorf("fcimport: amount %q is not rupees with at most two decimals", s)
	}
	rupees, err := strconv.ParseInt(m[1], 10, 64)
	if err != nil {
		return 0, fmt.Errorf("fcimport: amount %q: %w", s, err)
	}
	frac := (m[2] + "00")[:2]
	p, _ := strconv.ParseInt(frac, 10, 64) // two digits, matched by the regexp
	total := rupees*100 + p
	if total <= 0 {
		return 0, fmt.Errorf("fcimport: amount %q is not positive", s)
	}
	return total, nil
}

// fcCategories maps FC's own category names, as its screen showed them, to his category ids.
var fcCategories = map[string]int64{
	"Food": 1, "Drinks": 1, // into Food & Drinks (his call)
	"Transportation": 3, "Housing": 4, "Medical": 5, "Entertainment": 6, "Shopping": 7,
	"Misc.": 11, // into Misc (his call)
	"GYM":   1001, "EMI": 1002, "bae": 1003, "ph recharg": 1004, "Electronics": 1005,
	"gift": 1006, "Accessorie": 1007,
}

// CategoryFor is the entry's category id. 2026's gifts go to bae (spec ruling 5a).
func CategoryFor(e Entry) (int64, error) {
	id, ok := fcCategories[e.Category]
	if !ok {
		return 0, fmt.Errorf("fcimport: unknown Fortune City category %q", e.Category)
	}
	if id == 1006 && strings.HasPrefix(e.Date, "2026-") {
		return 1003, nil
	}
	return id, nil
}

// HistoryPayee is the one payee every imported entry is paid to: FC never recorded payees.
var HistoryPayee = store.Payee{ID: 1, Name: "Offline payment history"}

// Plan turns entries into txns, all or nothing. Times: 12:00 in zone on FC's date, one minute
// apart in file order within the date (FC's order_in_day counts within a category, not a day).
func Plan(entries []Entry, now int64, zone *time.Location) ([]store.Txn, error) {
	txns := make([]store.Txn, 0, len(entries))
	perDate := map[string]int64{}
	for i, e := range entries {
		day, err := time.ParseInLocation("2006-01-02", e.Date, zone)
		if err != nil {
			return nil, fmt.Errorf("fcimport: row %d: date %q: %w", i, e.Date, err)
		}
		cat, err := CategoryFor(e)
		if err != nil {
			return nil, err
		}
		paise, err := Paise(e.AmountINR)
		if err != nil {
			return nil, fmt.Errorf("row %d: %w", i, err)
		}
		at := day.Add(12*time.Hour + time.Duration(perDate[e.Date])*time.Minute).UnixMilli()
		perDate[e.Date]++
		note := e.Note
		txns = append(txns, store.Txn{
			UUID: entryUUID(e), AmountPaise: paise, PayeeID: HistoryPayee.ID, CategoryID: cat,
			Source: "IMPORT", Status: "LOGGED", Regret: "UNRATED", Note: &note,
			OccurredAt: at, CreatedAt: now, UpdatedAt: now,
		})
	}
	return txns, nil
}

var uuidNamespace = func() [16]byte {
	sum := sha1.Sum([]byte("expense-garden/fc"))
	var ns [16]byte
	copy(ns[:], sum[:16])
	return ns
}()

// entryUUID is a UUIDv5 of FC's raw fields, so the same entry always gets the same id and a
// re-import (or a longer export) adds only what is new. Raw: never the mapped category.
func entryUUID(e Entry) string {
	h := sha1.New()
	h.Write(uuidNamespace[:])
	fmt.Fprintf(h, "%s|%s|%d|%s|%s", e.Date, e.Category, e.OrderInDay, e.AmountINR, e.Note)
	var b [16]byte
	copy(b[:], h.Sum(nil))
	b[6] = b[6]&0x0f | 0x50
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// Import writes his categories, the history payee and every entry under userID in one
// transaction. Nothing already there is touched, so a re-import adds only new entries.
func Import(ctx context.Context, s *store.Store, userID int64, entries []Entry, now int64) (Summary, error) {
	txns, err := Plan(entries, now, indiaZone)
	if err != nil {
		return Summary{}, err
	}
	payee := HistoryPayee
	payee.UpdatedAt = now
	inserted, err := s.ImportHistory(ctx, userID, Categories(now), payee, txns)
	if err != nil {
		return Summary{}, err
	}
	sum := Summary{Inserted: inserted, Skipped: len(txns) - inserted, ByCategory: map[int64]CategoryTotal{}}
	for _, t := range txns {
		c := sum.ByCategory[t.CategoryID]
		c.Count++
		c.Paise += t.AmountPaise
		sum.ByCategory[t.CategoryID] = c
	}
	return sum, nil
}

// indiaZone is where he lived every FC date. time/tzdata embeds the zone database, so loading
// a fixed, valid name cannot fail on any host.
var indiaZone = func() *time.Location {
	z, err := time.LoadLocation("Asia/Kolkata")
	if err != nil {
		panic(err)
	}
	return z
}()
