// Package admin is what cmd/admin runs: creating users, setting their tokens, importing history.
package admin

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"

	"expensegarden/core-api/internal/store"
)

// CreateUser adds a user with a fresh token written to tokenFile. The file is written first and
// removed again if the database refuses, so a failure never leaves a token for nobody.
func CreateUser(ctx context.Context, s *store.Store, name, tokenFile string) error {
	return withNewToken(tokenFile, func(hash []byte) error {
		_, err := s.CreateUser(ctx, name, hash)
		return err
	})
}

// SetToken gives an existing user a fresh token written to tokenFile; the old one stops working.
func SetToken(ctx context.Context, s *store.Store, name, tokenFile string) error {
	return withNewToken(tokenFile, func(hash []byte) error {
		return s.SetTokenHash(ctx, name, hash)
	})
}

func withNewToken(tokenFile string, save func(hash []byte) error) error {
	token, err := writeNewToken(tokenFile)
	if err != nil {
		return err
	}
	if err := save(store.HashToken(token)); err != nil {
		return errors.Join(err, os.Remove(tokenFile))
	}
	return nil
}

// writeNewToken writes 32 random bytes, base64, to a new 0600 file with no trailing newline (the
// phone trims, but a newline still trips anything that compares raw). It never overwrites a file.
func writeNewToken(path string) (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	token := base64.StdEncoding.EncodeToString(raw)
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return "", fmt.Errorf("token file: %w", err)
	}
	if _, err := f.WriteString(token); err != nil {
		return "", errors.Join(err, f.Close(), os.Remove(path))
	}
	return token, f.Close()
}
