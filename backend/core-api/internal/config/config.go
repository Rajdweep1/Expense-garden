// Package config reads the handful of settings core-api needs, all from the environment.
// Nothing is defaulted that would be dangerous if wrong: a missing token is a startup
// failure rather than an open server.
package config

import (
	"errors"
	"os"
)

type Config struct {
	DatabaseURL string
	Token       string
	Addr        string
}

var ErrMissing = errors.New("config: DATABASE_URL and SYNC_TOKEN are both required")

func Load() (Config, error) {
	c := Config{
		DatabaseURL: os.Getenv("DATABASE_URL"),
		Token:       os.Getenv("SYNC_TOKEN"),
		Addr:        os.Getenv("ADDR"),
	}
	if c.Addr == "" {
		c.Addr = ":8080"
	}
	if c.DatabaseURL == "" || c.Token == "" {
		return c, ErrMissing
	}
	return c, nil
}
