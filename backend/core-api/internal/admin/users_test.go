package admin

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"expensegarden/core-api/internal/store"
	"expensegarden/core-api/internal/testdb"
)

func newStore(t *testing.T) *store.Store {
	t.Helper()
	return &store.Store{Pool: testdb.Pool(t)}
}

// readToken checks the file the way AC-5 asks: 0600, no trailing newline, and returns its token.
func readToken(t *testing.T, path string) string {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("token file mode %o, want 600", info.Mode().Perm())
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(b) < 43 || b[len(b)-1] == '\n' {
		t.Fatalf("token file holds %d bytes (newline at end: %v); want a bare 32-byte base64 token", len(b), len(b) > 0 && b[len(b)-1] == '\n')
	}
	return string(b)
}

func TestCreateUserWritesA0600TokenThatAuthenticates(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "a.token")
	if err := CreateUser(ctx, s, "a", path); err != nil {
		t.Fatal(err)
	}
	token := readToken(t, path)
	id, ok, err := s.UserByToken(ctx, token)
	if err != nil || !ok || id == 0 {
		t.Fatalf("the written token does not authenticate: (%d, %v, %v)", id, ok, err)
	}
	var stored []byte
	if err := s.Pool.QueryRow(ctx, `SELECT token_sha256 FROM app_user WHERE id = $1`, id).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if string(stored) == token || len(stored) != 32 {
		t.Fatal("the database holds the token itself, not its SHA-256")
	}
}

func TestCreateUserRefusesAnExistingFile(t *testing.T) {
	s := newStore(t)
	path := filepath.Join(t.TempDir(), "a.token")
	if err := os.WriteFile(path, []byte("keep me"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := CreateUser(context.Background(), s, "a", path); err == nil {
		t.Fatal("create-user overwrote an existing file")
	}
	if b, _ := os.ReadFile(path); string(b) != "keep me" {
		t.Fatal("the existing file was changed")
	}
	var n int
	_ = s.Pool.QueryRow(context.Background(), `SELECT COUNT(*) FROM app_user WHERE name = 'a'`).Scan(&n)
	if n != 0 {
		t.Fatal("a user was created anyway")
	}
}

func TestCreateUserRefusesAnExistingName(t *testing.T) {
	s := newStore(t)
	dir := t.TempDir()
	if err := CreateUser(context.Background(), s, "a", filepath.Join(dir, "1")); err != nil {
		t.Fatal(err)
	}
	if err := CreateUser(context.Background(), s, "a", filepath.Join(dir, "2")); err == nil {
		t.Fatal("a second user with the same name was created")
	}
	if _, err := os.Stat(filepath.Join(dir, "2")); !os.IsNotExist(err) {
		t.Fatal("a refused create-user left a token file behind")
	}
}

func TestSetTokenReplacesTheOldToken(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	dir := t.TempDir()
	if err := CreateUser(ctx, s, "a", filepath.Join(dir, "old")); err != nil {
		t.Fatal(err)
	}
	old := readToken(t, filepath.Join(dir, "old"))
	if err := SetToken(ctx, s, "a", filepath.Join(dir, "new")); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := s.UserByToken(ctx, old); ok {
		t.Fatal("the old token still works")
	}
	if _, ok, _ := s.UserByToken(ctx, readToken(t, filepath.Join(dir, "new"))); !ok {
		t.Fatal("the new token does not work")
	}
}

func TestSetTokenOnAMissingUserFails(t *testing.T) {
	s := newStore(t)
	path := filepath.Join(t.TempDir(), "x")
	if err := SetToken(context.Background(), s, "nobody", path); err == nil {
		t.Fatal("set-token succeeded for a user that doesn't exist")
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("a failed set-token left a token file behind")
	}
}
