package config

import (
	"errors"
	"testing"
)

// The shape `openssl rand -base64 32` produces (runbook §3): 44 characters.
const generated = "q5Zx2vS8nB1fK7mW3tJ9hL0cR6yA4uE8pG2dV5oN1iM="

func load(t *testing.T, token string) (Config, error) {
	t.Helper()
	t.Setenv("DATABASE_URL", "postgres://localhost/expense_garden")
	t.Setenv("SYNC_TOKEN", token)
	return Load()
}

func TestAGeneratedTokenIsAccepted(t *testing.T) {
	if c, err := load(t, generated); err != nil || c.Token != generated {
		t.Fatalf("want the token and no error, got %q, %v", c.Token, err)
	}
}

// The phone trims its copy (SyncPrefs.token), so a token pasted into the host's settings with
// a trailing newline matched nothing: every push answered 401, which the phone treats as silence.
func TestTheTokenIsTrimmed(t *testing.T) {
	if c, err := load(t, "  "+generated+"\n"); err != nil || c.Token != generated {
		t.Fatalf("want the trimmed token and no error, got %q, %v", c.Token, err)
	}
}

func TestABlankTokenIsMissing(t *testing.T) {
	if _, err := load(t, " \n\t"); !errors.Is(err, ErrMissing) {
		t.Fatalf("want ErrMissing, got %v", err)
	}
}

func TestAShortTokenIsRefused(t *testing.T) {
	// The value the 2A plan's local runs used: twenty characters, typed by hand.
	if _, err := load(t, "dev-token-not-secret"); !errors.Is(err, ErrWeakToken) {
		t.Fatalf("want ErrWeakToken, got %v", err)
	}
}

func TestTheDatabaseURLIsRequired(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	t.Setenv("SYNC_TOKEN", generated)
	if _, err := Load(); !errors.Is(err, ErrMissing) {
		t.Fatalf("want ErrMissing, got %v", err)
	}
}
