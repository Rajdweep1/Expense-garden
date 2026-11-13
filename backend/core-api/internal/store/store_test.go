package store

import (
	"context"
	"testing"

	"expensegarden/core-api/internal/testdb"
)

// The last-write-wins rule lives in SQL, so it is tested against real Postgres. A mock would
// only prove that the mock agrees with itself.
func newTestStore(t *testing.T) *Store {
	t.Helper()
	return &Store{Pool: testdb.Pool(t)}
}

func newUser(t *testing.T, s *Store, name string) int64 {
	t.Helper()
	id, err := s.CreateUser(context.Background(), name, nil)
	if err != nil {
		t.Fatalf("create user %s: %v", name, err)
	}
	return id
}

func seedCategory(t *testing.T, s *Store, uid int64) {
	t.Helper()
	if err := s.ApplyBatch(context.Background(), uid, Batch{
		Categories: []Category{{ID: 3, Name: "Transport", IsNecessity: true, UpdatedAt: 1}},
	}); err != nil {
		t.Fatalf("seed: %v", err)
	}
}

func ptr[T any](v T) *T { return &v }

func TestParseBudgetRowKey(t *testing.T) {
	cases := []struct {
		key     string
		wantNil bool
		wantID  int64
		wantMon string
		wantOK  bool
	}{
		{"3|2026-09", false, 3, "2026-09", true},
		{"*|2026-09", true, 0, "2026-09", true},
		{"garbage", false, 0, "", false},
		{"x|2026-09", false, 0, "", false},
		{"3|", false, 0, "", false},
	}
	for _, c := range cases {
		id, month, ok := ParseBudgetRowKey(c.key)
		if ok != c.wantOK {
			t.Fatalf("%q: ok = %v, want %v", c.key, ok, c.wantOK)
		}
		if !ok {
			continue
		}
		if month != c.wantMon {
			t.Fatalf("%q: month = %q, want %q", c.key, month, c.wantMon)
		}
		if c.wantNil && id != nil {
			t.Fatalf("%q: expected NULL category", c.key)
		}
		if !c.wantNil && (id == nil || *id != c.wantID) {
			t.Fatalf("%q: id mismatch", c.key)
		}
	}
}

func TestNewerWriteWins(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	seedCategory(t, s, uid)

	for _, b := range []Budget{
		{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 100, UpdatedAt: 10},
		{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 200, UpdatedAt: 20},
	} {
		if err := s.ApplyBatch(ctx, uid, Batch{Budgets: []Budget{b}}); err != nil {
			t.Fatal(err)
		}
	}

	var amount int64
	if err := s.Pool.QueryRow(ctx, `SELECT amount_paise FROM budget WHERE category_id = 3`).Scan(&amount); err != nil {
		t.Fatal(err)
	}
	if amount != 200 {
		t.Fatalf("amount = %d, want 200", amount)
	}
}

func TestOlderWriteIsIgnored(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	seedCategory(t, s, uid)

	for _, b := range []Budget{
		{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 200, UpdatedAt: 20},
		{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 100, UpdatedAt: 10},
	} {
		if err := s.ApplyBatch(ctx, uid, Batch{Budgets: []Budget{b}}); err != nil {
			t.Fatal(err)
		}
	}

	var amount int64
	if err := s.Pool.QueryRow(ctx, `SELECT amount_paise FROM budget WHERE category_id = 3`).Scan(&amount); err != nil {
		t.Fatal(err)
	}
	if amount != 200 {
		t.Fatalf("a stale write overwrote a newer one: amount = %d, want 200", amount)
	}
}

// The reason spec §2.1 insists on NULLS NOT DISTINCT: without it, every overall budget for a
// month is a distinct row, ON CONFLICT never fires, and duplicates pile up unnoticed.
func TestOverallBudgetIsUniquePerMonth(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	seedCategory(t, s, uid)

	for _, b := range []Budget{
		{CategoryID: nil, Month: "2026-09", AmountPaise: 500, UpdatedAt: 10},
		{CategoryID: nil, Month: "2026-09", AmountPaise: 900, UpdatedAt: 20},
	} {
		if err := s.ApplyBatch(ctx, uid, Batch{Budgets: []Budget{b}}); err != nil {
			t.Fatal(err)
		}
	}

	var count, amount int64
	if err := s.Pool.QueryRow(ctx,
		`SELECT COUNT(*), MAX(amount_paise) FROM budget WHERE category_id IS NULL`).Scan(&count, &amount); err != nil {
		t.Fatal(err)
	}
	if count != 1 || amount != 900 {
		t.Fatalf("count = %d amount = %d, want 1 and 900", count, amount)
	}
}

func TestStaleTombstoneDoesNotDeleteANewerBudget(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	seedCategory(t, s, uid)

	if err := s.ApplyBatch(ctx, uid, Batch{
		Budgets: []Budget{{CategoryID: ptr(int64(3)), Month: "2026-09", AmountPaise: 700, UpdatedAt: 50}},
	}); err != nil {
		t.Fatal(err)
	}
	// A delete that happened BEFORE the row's latest write must not erase it.
	if err := s.ApplyBatch(ctx, uid, Batch{
		Tombstones: []Tombstone{{TableName: "budget", RowKey: "3|2026-09", DeletedAt: 40}},
	}); err != nil {
		t.Fatal(err)
	}

	var count int64
	if err := s.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM budget WHERE category_id = 3`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatal("stale tombstone deleted a newer budget")
	}
}

func TestFreshTombstoneDeletesTheOverallBudget(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	seedCategory(t, s, uid)

	if err := s.ApplyBatch(ctx, uid, Batch{
		Budgets: []Budget{{CategoryID: nil, Month: "2026-09", AmountPaise: 700, UpdatedAt: 50}},
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.ApplyBatch(ctx, uid, Batch{
		Tombstones: []Tombstone{{TableName: "budget", RowKey: "*|2026-09", DeletedAt: 60}},
	}); err != nil {
		t.Fatal(err)
	}

	var count int64
	if err := s.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM budget WHERE category_id IS NULL`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("tombstone did not delete the overall budget")
	}
}

func TestResendingAnEventIsANoOp(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")

	ev := Event{ID: 1, Type: "gate.dodged", PayloadJSON: "{}", CreatedAt: 5}
	for i := 0; i < 2; i++ {
		if err := s.ApplyBatch(ctx, uid, Batch{Events: []Event{ev}}); err != nil {
			t.Fatal(err)
		}
	}

	var count int64
	if err := s.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM game_event`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("count = %d, want 1 — retry must be idempotent", count)
	}
}

func TestSnapshotRoundTripsEveryTable(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	seedCategory(t, s, uid)

	if err := s.ApplyBatch(ctx, uid, Batch{
		Payees: []Payee{{ID: 7, Name: "Chaiwala", VPA: ptr("chai@ybl"), UpdatedAt: 5}},
		Txns: []Txn{{
			UUID: "u1", AmountPaise: 2000, PayeeID: 7, CategoryID: 3, Source: "MANUAL",
			Status: "LOGGED", Regret: "UNRATED", OccurredAt: 1, CreatedAt: 1, UpdatedAt: 5,
		}},
		Budgets: []Budget{{CategoryID: nil, Month: "2026-09", AmountPaise: 1000, UpdatedAt: 5}},
		Events:  []Event{{ID: 1, Type: "gate.dodged", PayloadJSON: "{}", CreatedAt: 5}},
	}); err != nil {
		t.Fatal(err)
	}

	snap, err := s.Snapshot(ctx, uid)
	if err != nil {
		t.Fatal(err)
	}
	if len(snap.Categories) != 1 || len(snap.Payees) != 1 || len(snap.Txns) != 1 ||
		len(snap.Budgets) != 1 || len(snap.Events) != 1 {
		t.Fatalf("snapshot lost a table: %+v", snap)
	}
	if snap.Txns[0].UUID != "u1" || snap.Payees[0].VPA == nil || *snap.Payees[0].VPA != "chai@ybl" {
		t.Fatal("snapshot mangled a value")
	}
	if snap.Budgets[0].CategoryID != nil {
		t.Fatal("the overall budget's NULL category came back non-nil")
	}
}

// The phone's "test connection" is an empty push (settings plan, ruling 2, 2026-11-10): it must
// succeed and write nothing, or every Save would report the backup as broken.
func TestAnEmptyBatchApplies(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	if err := s.ApplyBatch(ctx, uid, Batch{}); err != nil {
		t.Fatalf("an empty batch was refused: %v", err)
	}
	snap, err := s.Snapshot(ctx, uid)
	if err != nil {
		t.Fatal(err)
	}
	if n := len(snap.Categories) + len(snap.Payees) + len(snap.Txns) + len(snap.Budgets) + len(snap.Events); n != 0 {
		t.Fatalf("an empty batch wrote %d rows", n)
	}
}
