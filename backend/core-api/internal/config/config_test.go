package config

import (
	"errors"
	"testing"
)

func TestTheDatabaseURLIsRequired(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	if _, err := Load(); !errors.Is(err, ErrMissing) {
		t.Fatalf("want ErrMissing, got %v", err)
	}
}

// Tokens live in app_user now; a deploy that still sets SYNC_TOKEN must start, not fail.
func TestOnlyTheDatabaseURLIsRequired(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://localhost/expense_garden")
	t.Setenv("SYNC_TOKEN", "")
	c, err := Load()
	if err != nil || c.DatabaseURL != "postgres://localhost/expense_garden" || c.Addr != ":8080" {
		t.Fatalf("want the url, :8080 and no error, got %+v, %v", c, err)
	}
}
