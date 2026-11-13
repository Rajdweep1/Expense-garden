package migrations

import (
	"context"
	"os"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

// emptyDB drops everything in the scratch database. Shares TEST_DATABASE_URL with the other DB
// tests, so run with `go test -p 1`.
func emptyDB(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	pool, err := pgxpool.New(context.Background(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if _, err := pool.Exec(context.Background(), `DROP SCHEMA public CASCADE; CREATE SCHEMA public`); err != nil {
		t.Fatal(err)
	}
	return pool
}

func count(t *testing.T, pool *pgxpool.Pool, q string) int64 {
	t.Helper()
	var n int64
	if err := pool.QueryRow(context.Background(), q).Scan(&n); err != nil {
		t.Fatalf("%s: %v", q, err)
	}
	return n
}

func TestMigrationsApplyOnAnEmptyDatabase(t *testing.T) {
	pool := emptyDB(t)
	if err := Apply(context.Background(), pool); err != nil {
		t.Fatal(err)
	}
	if n := count(t, pool, `SELECT COUNT(*) FROM app_user WHERE name = 'test'`); n != 1 {
		t.Fatalf("want the test user, got %d", n)
	}
}

// AC-1: every row the 2A schema held moves under the test user; none is lost.
func TestMigrationBackfillsExistingRowsToTest(t *testing.T) {
	pool := emptyDB(t)
	ctx := context.Background()
	if err := applyThrough(ctx, pool, "001_init.sql"); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `
		INSERT INTO category VALUES (1, 'Food & Drinks', NULL, false, 1), (101, 'Restaurants', 1, false, 1);
		INSERT INTO payee VALUES (7, 'Chaiwala', 'chai@ybl', 1, 1);
		INSERT INTO txn VALUES ('u1', 2000, 7, 101, 'MANUAL', 'LOGGED', 'UNRATED', false, 'tea', 1, 1, 1);
		INSERT INTO budget VALUES (NULL, '2026-09', 500, 1), (1, '2026-09', 100, 1);
		INSERT INTO game_event VALUES (1, 'transaction.logged', '{}', 'u1', 1), (2, 'gate.dodged', '{}', NULL, 1);
	`); err != nil {
		t.Fatal(err)
	}
	tables := map[string]int64{"category": 2, "payee": 1, "txn": 1, "budget": 2, "game_event": 2}

	if err := Apply(ctx, pool); err != nil {
		t.Fatal(err)
	}
	for tbl, want := range tables {
		got := count(t, pool, `SELECT COUNT(*) FROM `+tbl+` WHERE user_id = (SELECT id FROM app_user WHERE name = 'test')`)
		if got != want {
			t.Fatalf("%s: %d rows under test, want %d", tbl, got, want)
		}
	}
}
