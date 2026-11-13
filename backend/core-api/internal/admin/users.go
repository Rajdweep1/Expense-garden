// Package admin is what cmd/admin runs: creating users, setting their tokens, importing history.
package admin

import (
	"context"
	"errors"

	"expensegarden/core-api/internal/store"
)

// CreateUser adds a user with a fresh token written to tokenFile.
func CreateUser(ctx context.Context, s *store.Store, name, tokenFile string) error {
	return errors.New("not implemented")
}

// SetToken gives an existing user a fresh token written to tokenFile.
func SetToken(ctx context.Context, s *store.Store, name, tokenFile string) error {
	return errors.New("not implemented")
}
