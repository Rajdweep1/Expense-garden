package store

import (
	"context"
	"testing"
)

// fullBatch is one row in every table, keyed by the ids every phone assigns: category 3 from the
// seed, payee 7 and event 1 from SQLite rowids. Two users pushing it must never collide.
func fullBatch(payee, note string, amount, stamp int64) Batch {
	return Batch{
		Categories: []Category{{ID: 3, Name: "Transport", IsNecessity: true, UpdatedAt: stamp}},
		Payees:     []Payee{{ID: 7, Name: payee, VPA: ptr("shop@ybl"), UpdatedAt: stamp}},
		Txns: []Txn{{
			UUID: "u1", AmountPaise: amount, PayeeID: 7, CategoryID: 3, Source: "MANUAL",
			Status: "LOGGED", Regret: "UNRATED", Note: ptr(note), OccurredAt: 1, CreatedAt: 1, UpdatedAt: stamp,
		}},
		Budgets: []Budget{{CategoryID: nil, Month: "2026-09", AmountPaise: amount, UpdatedAt: stamp}},
		Events:  []Event{{ID: 1, Type: "transaction.logged", PayloadJSON: "{}", TransactionUUID: ptr("u1"), CreatedAt: stamp}},
	}
}

func snapshotOf(t *testing.T, s *Store, uid int64) Batch {
	t.Helper()
	b, err := s.Snapshot(context.Background(), uid)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestUsersWithTheSameIdsDoNotCollide(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	a, b := newUser(t, s, "a"), newUser(t, s, "b")

	if err := s.ApplyBatch(ctx, a, fullBatch("A shop", "a's", 100, 5)); err != nil {
		t.Fatalf("a: %v", err)
	}
	if err := s.ApplyBatch(ctx, b, fullBatch("B shop", "b's", 999, 5)); err != nil {
		t.Fatalf("b: %v", err)
	}

	for _, c := range []struct {
		uid    int64
		payee  string
		amount int64
	}{{a, "A shop", 100}, {b, "B shop", 999}} {
		snap := snapshotOf(t, s, c.uid)
		if len(snap.Categories) != 1 || len(snap.Payees) != 1 || len(snap.Txns) != 1 ||
			len(snap.Budgets) != 1 || len(snap.Events) != 1 {
			t.Fatalf("user %d: want one row per table, got %+v", c.uid, snap)
		}
		if snap.Payees[0].Name != c.payee || snap.Txns[0].AmountPaise != c.amount || snap.Budgets[0].AmountPaise != c.amount {
			t.Fatalf("user %d got another user's values: %+v", c.uid, snap)
		}
	}
}

func TestOneUserCannotOverwriteAnother(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	a, b := newUser(t, s, "a"), newUser(t, s, "b")

	if err := s.ApplyBatch(ctx, a, fullBatch("A shop", "a's", 100, 5)); err != nil {
		t.Fatal(err)
	}
	// Newer stamps: under last-write-wins these would win if the keys were shared.
	if err := s.ApplyBatch(ctx, b, fullBatch("B shop", "b's", 999, 9)); err != nil {
		t.Fatal(err)
	}
	if err := s.ApplyBatch(ctx, b, Batch{
		Tombstones: []Tombstone{{TableName: "budget", RowKey: "*|2026-09", DeletedAt: 99}},
	}); err != nil {
		t.Fatal(err)
	}

	snap := snapshotOf(t, s, a)
	if snap.Txns[0].AmountPaise != 100 || snap.Payees[0].Name != "A shop" {
		t.Fatalf("b's push overwrote a's rows: %+v", snap)
	}
	if len(snap.Budgets) != 1 {
		t.Fatal("b's tombstone deleted a's overall budget")
	}
	if len(snapshotOf(t, s, b).Budgets) != 0 {
		t.Fatal("b's tombstone did not delete b's own budget")
	}
}

func TestSnapshotHoldsOnlyTheCallersRows(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	a, b := newUser(t, s, "a"), newUser(t, s, "b")
	if err := s.ApplyBatch(ctx, a, fullBatch("A shop", "a's", 100, 5)); err != nil {
		t.Fatal(err)
	}
	snap := snapshotOf(t, s, b)
	if n := len(snap.Categories) + len(snap.Payees) + len(snap.Txns) + len(snap.Budgets) + len(snap.Events); n != 0 {
		t.Fatalf("b's snapshot holds %d of a's rows", n)
	}
}

// A push that points at another user's row is refused whole: the foreign keys carry user_id.
func TestARowCannotPointAtAnotherUsersCategory(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	a, b := newUser(t, s, "a"), newUser(t, s, "b")
	seedCategory(t, s, a)

	err := s.ApplyBatch(ctx, b, Batch{
		Payees: []Payee{{ID: 1, Name: "B shop", UpdatedAt: 5}},
		Txns: []Txn{{UUID: "b1", AmountPaise: 1, PayeeID: 1, CategoryID: 3, Source: "MANUAL",
			Status: "LOGGED", Regret: "UNRATED", OccurredAt: 1, CreatedAt: 1, UpdatedAt: 5}},
	})
	if err == nil {
		t.Fatal("b's txn was accepted against a's category")
	}
	if snap := snapshotOf(t, s, b); len(snap.Payees) != 0 {
		t.Fatal("the refused batch was half applied")
	}
}

func TestARowCannotPointAtAnotherUsersPayeeOrTxn(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	a, b := newUser(t, s, "a"), newUser(t, s, "b")
	if err := s.ApplyBatch(ctx, a, fullBatch("A shop", "a's", 100, 5)); err != nil {
		t.Fatal(err)
	}
	seedCategory(t, s, b)

	if err := s.ApplyBatch(ctx, b, Batch{Txns: []Txn{{UUID: "b1", AmountPaise: 1, PayeeID: 7, CategoryID: 3,
		Source: "MANUAL", Status: "LOGGED", Regret: "UNRATED", OccurredAt: 1, CreatedAt: 1, UpdatedAt: 5}}}); err == nil {
		t.Fatal("b's txn was accepted against a's payee")
	}
	if err := s.ApplyBatch(ctx, b, Batch{Events: []Event{{ID: 2, Type: "transaction.logged", PayloadJSON: "{}",
		TransactionUUID: ptr("u1"), CreatedAt: 5}}}); err == nil {
		t.Fatal("b's event was accepted against a's txn")
	}
}

func TestUserByTokenHash(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	id, err := s.CreateUser(ctx, "a", []byte("hash-of-a"))
	if err != nil {
		t.Fatal(err)
	}
	newUser(t, s, "no-token") // NULL hash

	if got, ok, err := s.UserByTokenHash(ctx, []byte("hash-of-a")); err != nil || !ok || got != id {
		t.Fatalf("want (%d, true, nil), got (%d, %v, %v)", id, got, ok, err)
	}
	for _, h := range [][]byte{[]byte("unknown"), nil, {}} {
		if got, ok, err := s.UserByTokenHash(ctx, h); err != nil || ok || got != 0 {
			t.Fatalf("%q: want (0, false, nil), got (%d, %v, %v)", h, got, ok, err)
		}
	}
}

func TestSetTokenHash(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	id := newUser(t, s, "a")
	if err := s.SetTokenHash(ctx, "a", []byte("new")); err != nil {
		t.Fatal(err)
	}
	if got, ok, _ := s.UserByTokenHash(ctx, []byte("new")); !ok || got != id {
		t.Fatal("the new hash does not find the user")
	}
	if err := s.SetTokenHash(ctx, "nobody", []byte("x")); err != ErrNoSuchUser {
		t.Fatalf("want ErrNoSuchUser, got %v", err)
	}
}

// The hash the server looks a token up by. Whitespace is trimmed first, as the phone does.
func TestUserByTokenTrimsAndHashes(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	id, err := s.CreateUser(ctx, "a", HashToken("secret-token"))
	if err != nil {
		t.Fatal(err)
	}
	for _, tok := range []string{"secret-token", " secret-token\n"} {
		if got, ok, err := s.UserByToken(ctx, tok); err != nil || !ok || got != id {
			t.Fatalf("%q: want (%d, true, nil), got (%d, %v, %v)", tok, id, got, ok, err)
		}
	}
	if string(HashToken("secret-token")) == "secret-token" || len(HashToken("x")) != 32 {
		t.Fatal("HashToken must be a SHA-256, not the token")
	}
}

// A row the database refuses partway through must take the whole import with it.
func TestImportHistoryRollsBackOnAFailedRow(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	uid := newUser(t, s, "a")
	ok := Txn{UUID: "ok", AmountPaise: 1, PayeeID: 1, CategoryID: 3, Source: "IMPORT", Status: "LOGGED",
		Regret: "UNRATED", OccurredAt: 1, CreatedAt: 1, UpdatedAt: 1}
	bad := ok
	bad.UUID, bad.CategoryID = "bad", 9999
	_, err := s.ImportHistory(ctx, uid, []Category{{ID: 3, Name: "Transport", IsNecessity: true, UpdatedAt: 1}},
		Payee{ID: 1, Name: "Offline payment history", UpdatedAt: 1}, []Txn{ok, bad})
	if err == nil {
		t.Fatal("a txn with an unknown category was accepted")
	}
	snap := snapshotOf(t, s, uid)
	if len(snap.Txns)+len(snap.Categories)+len(snap.Payees) != 0 {
		t.Fatalf("a failed import left rows behind: %+v", snap)
	}
}

func TestUserIDByName(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	id := newUser(t, s, "a")
	if got, err := s.UserIDByName(ctx, "a"); err != nil || got != id {
		t.Fatalf("want (%d, nil), got (%d, %v)", id, got, err)
	}
	if _, err := s.UserIDByName(ctx, "nobody"); err != ErrNoSuchUser {
		t.Fatalf("want ErrNoSuchUser, got %v", err)
	}
}
