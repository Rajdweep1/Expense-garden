// Package config reads the handful of settings core-api needs, all from the environment.
// Nothing is defaulted that would be dangerous if wrong: a missing or weak token is a startup
// failure rather than an open server.
package config

import (
	"errors"
	"os"
	"strings"
)

type Config struct {
	DatabaseURL string
	Token       string
	Addr        string
}

var ErrMissing = errors.New("config: DATABASE_URL and SYNC_TOKEN are both required")

var ErrWeakToken = errors.New("config: SYNC_TOKEN must be at least 32 characters; generate one with: openssl rand -base64 32")

// minTokenLen is the floor for SYNC_TOKEN. The runbook's `openssl rand -base64 32` gives 44
// characters; a token anyone could type from memory gives far fewer. The token is the only
// thing between the ledger and the internet.
const minTokenLen = 32

func Load() (Config, error) {
	c := Config{
		DatabaseURL: os.Getenv("DATABASE_URL"),
		// Trimmed because the phone trims its copy: a token pasted with a trailing newline would
		// otherwise match nothing, and the phone turns every refusal into silence.
		Token: strings.TrimSpace(os.Getenv("SYNC_TOKEN")),
		Addr:  os.Getenv("ADDR"),
	}
	if c.Addr == "" {
		c.Addr = ":8080"
	}
	if c.DatabaseURL == "" || c.Token == "" {
		return c, ErrMissing
	}
	if len(c.Token) < minTokenLen {
		return c, ErrWeakToken
	}
	return c, nil
}
