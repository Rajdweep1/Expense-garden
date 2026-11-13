// Package config reads the handful of settings core-api needs, all from the environment.
// Tokens are not configuration: each user's lives (hashed) in app_user, set by cmd/admin.
package config

import (
	"errors"
	"os"
)

type Config struct {
	DatabaseURL string
	Addr        string
}

var ErrMissing = errors.New("config: DATABASE_URL is required")

func Load() (Config, error) {
	c := Config{DatabaseURL: os.Getenv("DATABASE_URL"), Addr: os.Getenv("ADDR")}
	if c.Addr == "" {
		c.Addr = ":8080"
	}
	if c.DatabaseURL == "" {
		return c, ErrMissing
	}
	return c, nil
}
