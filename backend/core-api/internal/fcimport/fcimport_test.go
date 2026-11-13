package fcimport

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"expensegarden/core-api/internal/store"
	"expensegarden/core-api/internal/testdb"
)

var ist = mustZone("Asia/Kolkata")

func mustZone(name string) *time.Location {
	z, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return z
}

func sample(t *testing.T) []Entry {
	t.Helper()
	f, err := os.Open("testdata/sample.json")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	es, err := Parse(f)
	if err != nil {
		t.Fatal(err)
	}
	return es
}

func TestPaise(t *testing.T) {
	for in, want := range map[string]int64{"10": 1000, "286414.5": 28641450, "0.01": 1, "6000": 600000, "120.50": 12050} {
		if got, err := Paise(in); err != nil || got != want {
			t.Fatalf("%q: want %d, got %d (%v)", in, want, got, err)
		}
	}
	for _, bad := range []string{"12.345", "-5", "", "1e3", "0", "0.00", " 10", "10.", ".5", "1,000"} {
		if _, err := Paise(bad); err == nil {
			t.Fatalf("%q was accepted", bad)
		}
	}
}

func TestEveryExportNameMaps(t *testing.T) {
	want := map[string]int64{
		"Food": 1, "Drinks": 1, "Transportation": 3, "Housing": 4, "Medical": 5, "Entertainment": 6,
		"Shopping": 7, "Misc.": 11, "GYM": 1001, "EMI": 1002, "bae": 1003, "ph recharg": 1004,
		"Electronics": 1005, "gift": 1006, "Accessorie": 1007,
	}
	for name, id := range want {
		if got, err := CategoryFor(Entry{Date: "2025-01-01", Category: name}); err != nil || got != id {
			t.Fatalf("%q: want %d, got %d (%v)", name, id, got, err)
		}
	}
}

// His call (spec ruling 5a): gift entries dated 2026 belong to bae.
func TestGift2026GoesToBae(t *testing.T) {
	if id, _ := CategoryFor(Entry{Date: "2026-01-03", Category: "gift"}); id != 1003 {
		t.Fatalf("a 2026 gift went to %d, want bae (1003)", id)
	}
	if id, _ := CategoryFor(Entry{Date: "2025-12-31", Category: "gift"}); id != 1006 {
		t.Fatalf("a 2025 gift went to %d, want gift (1006)", id)
	}
}

func TestUnknownCategoryRefusesThePlan(t *testing.T) {
	es := sample(t)
	es[3].Category = "Groceries"
	if _, err := Plan(es, 1, ist); err == nil || !strings.Contains(err.Error(), "Groceries") {
		t.Fatalf("want an error naming Groceries, got %v", err)
	}
}

// FC's order_in_day counts within a (date, category), so it can't order a day: times follow
// file order within the date instead (design critic finding 4).
func TestTimesFollowFileOrderWithinADate(t *testing.T) {
	txns, err := Plan(sample(t), 1, ist)
	if err != nil {
		t.Fatal(err)
	}
	noon := time.Date(2025, 3, 1, 12, 0, 0, 0, ist).UnixMilli()
	for i, want := range []int64{noon, noon + 60_000, noon + 120_000} {
		if txns[i].OccurredAt != want {
			t.Fatalf("row %d of 2025-03-01 at %d, want %d", i, txns[i].OccurredAt, want)
		}
	}
	if txns[3].OccurredAt != time.Date(2025, 6, 10, 12, 0, 0, 0, ist).UnixMilli() {
		t.Fatal("the first row of a new date doesn't start at 12:00 IST")
	}
}

func TestUUIDIsStableAndRaw(t *testing.T) {
	es := sample(t)
	a, _ := Plan(es, 1, ist)
	b, _ := Plan(es, 999, ist) // a different import time must not change identity
	if a[0].UUID != b[0].UUID || len(a[0].UUID) != 36 || a[0].UUID[14] != '5' {
		t.Fatalf("uuid not a stable v5: %q vs %q", a[0].UUID, b[0].UUID)
	}
	es[0].Note = "chai!"
	c, _ := Plan(es, 1, ist)
	if c[0].UUID == a[0].UUID {
		t.Fatal("a different note gave the same uuid")
	}
	seen := map[string]bool{}
	for _, tx := range a {
		if seen[tx.UUID] {
			t.Fatalf("duplicate uuid %s", tx.UUID)
		}
		seen[tx.UUID] = true
	}
}

func TestANonExpenseRowIsRefused(t *testing.T) {
	_, err := Parse(strings.NewReader(`[{"date":"2025-01-01","category":"Food","note":"x","amount_inr":"1","type":"income","source":"Fortune City","order_in_day":1}]`))
	if err == nil {
		t.Fatal("an income row was accepted")
	}
	_, err = Parse(strings.NewReader(`[{"date":"2025-01-01","category":"Food","note":"x","amount_inr":"1","type":"expense","source":"Fortune City","order_in_day":1,"extra":1}]`))
	if err == nil {
		t.Fatal("an unknown field was accepted")
	}
}

// ---------- against Postgres ----------

func newStore(t *testing.T) (*store.Store, int64) {
	t.Helper()
	s := &store.Store{Pool: testdb.Pool(t)}
	id, err := s.CreateUser(context.Background(), "him", nil)
	if err != nil {
		t.Fatal(err)
	}
	return s, id
}

func TestImportWritesExactlyTheFixture(t *testing.T) {
	s, uid := newStore(t)
	ctx := context.Background()
	es := sample(t)
	sum, err := Import(ctx, s, uid, es, 1_700_000_000_000)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Inserted != 8 || sum.Skipped != 0 {
		t.Fatalf("summary %+v, want 8 inserted", sum)
	}
	snap, err := s.Snapshot(ctx, uid)
	if err != nil {
		t.Fatal(err)
	}
	var total int64
	byCat := map[int64]int64{}
	for i, tx := range snap.Txns {
		total += tx.AmountPaise
		byCat[tx.CategoryID] += tx.AmountPaise
		if tx.Source != "IMPORT" || tx.Status != "LOGGED" || tx.Regret != "UNRATED" || tx.BreachedAtLogging || tx.PayeeID != 1 {
			t.Fatalf("txn %d fields: %+v", i, tx)
		}
	}
	if total != 232450 {
		t.Fatalf("total %d paise, want 232450", total)
	}
	want := map[int64]int64{1: 17050, 3: 2000, 1006: 30000, 1003: 150000, 11: 3500, 1004: 29900}
	for id, p := range want {
		if byCat[id] != p {
			t.Fatalf("category %d holds %d paise, want %d (all: %v)", id, byCat[id], p, byCat)
		}
	}
	notes := map[string]bool{}
	for _, tx := range snap.Txns {
		notes[*tx.Note] = true
	}
	for _, e := range es {
		if !notes[e.Note] {
			t.Fatalf("note %q not stored byte for byte", e.Note)
		}
	}
	if len(snap.Events) != 0 {
		t.Fatal("the import wrote garden events")
	}
	if len(snap.Payees) != 1 || snap.Payees[0].Name != "Offline payment history" || snap.Payees[0].VPA != nil {
		t.Fatalf("payee: %+v", snap.Payees)
	}
}

// AC-8: the 21 seed rows (two renamed, Food & Drinks a necessity) plus the seven custom ones.
func TestImportWritesHisCategories(t *testing.T) {
	s, uid := newStore(t)
	ctx := context.Background()
	if _, err := Import(ctx, s, uid, sample(t), 1); err != nil {
		t.Fatal(err)
	}
	snap, _ := s.Snapshot(ctx, uid)
	if len(snap.Categories) != 28 {
		t.Fatalf("%d categories, want 28", len(snap.Categories))
	}
	got := map[int64]store.Category{}
	for _, c := range snap.Categories {
		got[c.ID] = c
	}
	want := map[int64]struct {
		name string
		nec  bool
	}{
		1: {"Food & Drinks", true}, 3: {"Transportation", true}, 4: {"Housing", true}, 5: {"Medical", true},
		6: {"Entertainment", false}, 7: {"Shopping", false}, 11: {"Misc", false}, 101: {"Restaurants", false},
		1001: {"GYM", true}, 1002: {"EMI", false}, 1003: {"bae", false}, 1004: {"ph recharge", true},
		1005: {"Electronics", false}, 1006: {"gift", false}, 1007: {"Accessories", false},
	}
	for id, w := range want {
		if c, ok := got[id]; !ok || c.Name != w.name || c.IsNecessity != w.nec {
			t.Fatalf("category %d: got %+v, want %q necessity=%v", id, c, w.name, w.nec)
		}
	}
	if p := got[101].ParentID; p == nil || *p != 1 {
		t.Fatal("Restaurants lost its parent")
	}
}

func TestReimportAddsNothing(t *testing.T) {
	s, uid := newStore(t)
	ctx := context.Background()
	if _, err := Import(ctx, s, uid, sample(t), 100); err != nil {
		t.Fatal(err)
	}
	// A later rename on the phone must survive a re-import.
	if _, err := s.Pool.Exec(ctx, `UPDATE category SET name = 'Gym', updated_at = 500 WHERE user_id = $1 AND id = 1001`, uid); err != nil {
		t.Fatal(err)
	}
	sum, err := Import(ctx, s, uid, sample(t), 900)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Inserted != 0 || sum.Skipped != 8 {
		t.Fatalf("re-import summary %+v, want 0 inserted, 8 skipped", sum)
	}
	snap, _ := s.Snapshot(ctx, uid)
	for _, tx := range snap.Txns {
		if tx.UpdatedAt != 100 {
			t.Fatalf("a re-import restamped txn %s to %d", tx.UUID, tx.UpdatedAt)
		}
	}
	for _, c := range snap.Categories {
		if c.ID == 1001 && (c.Name != "Gym" || c.UpdatedAt != 500) {
			t.Fatalf("a re-import undid a rename: %+v", c)
		}
	}
}

func TestABadRowLeavesTheDatabaseUnchanged(t *testing.T) {
	s, uid := newStore(t)
	ctx := context.Background()
	es := sample(t)
	es[7].AmountINR = "12.345"
	if _, err := Import(ctx, s, uid, es, 1); err == nil {
		t.Fatal("a bad amount was imported")
	}
	snap, _ := s.Snapshot(ctx, uid)
	if len(snap.Txns)+len(snap.Categories)+len(snap.Payees) != 0 {
		t.Fatal("a refused import wrote rows")
	}
}
