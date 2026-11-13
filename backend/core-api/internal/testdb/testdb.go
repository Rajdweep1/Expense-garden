// Package testdb gives DB tests a migrated, empty scratch database.
//
// It returns a pool rather than a store so the store's own tests can use it without an import
// cycle. Every package that uses it shares one database, so run them with `go test -p 1`.
package testdb

import (
	"context"
	"os"
	"testing"

	"expensegarden/core-api/internal/migrations"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Pool connects to TEST_DATABASE_URL, applies the migrations and deletes every row.
// Never point it at a real database: it empties every table.
func Pool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set; see backend/core-api/README.md")
	}
	pool, err := pgxpool.New(context.Background(), dsn)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(pool.Close)
	if err := migrations.Apply(context.Background(), pool); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	// Children before parents; app_user last, since every table points at it.
	for _, tbl := range []string{"game_event", "budget", "txn", "payee", "category", "app_user"} {
		if _, err := pool.Exec(context.Background(), "DELETE FROM "+tbl); err != nil {
			t.Fatalf("clean %s: %v", tbl, err)
		}
	}
	return pool
}
