# Sync Token Hardening Implementation Plan (2B prerequisite)

> **For agentic workers:** execute inline with superpowers:executing-plans. Steps use checkbox
> (`- [ ]`) syntax. If a step's output doesn't match its Expected line: stop and report.

**Goal:** Before 2B puts core-api on the public internet, pin its auth boundary with tests and
close the three ways the bearer-token check can fail: an unset server token makes `"Bearer "` a
key; a token pasted with a trailing newline silently breaks sync; and nothing stops a short,
guessable token.

**Architecture:** The tests drive the real router (`Server.Routes()`), not just the middleware,
so they also prove which routes are guarded. They need no database: every server under test has a
nil `Store`, so a request that wrongly passes auth panics inside its handler, and the test helper
reports that as "reached its handler". Config trims the token and refuses one under 32 characters.
The middleware fails closed when the server token is empty.

**Tech Stack:** Go 1.27, standard library `testing` and `net/http/httptest`. No new dependencies.

---

## Why this plan exists

Read 2026-10-17 at `7356bcf`. `internal/httpapi` and `internal/config` have no tests (the
2026-10-11 `go test ./...` run printed `[no test files]` for both). The bearer token is the only
thing between the ledger and the internet, and `GET /v1/sync/snapshot` returns the whole ledger.

| # | Defect | Evidence |
|---|---|---|
| 1 | With an empty server token, `"Bearer "` authenticates. `strings.TrimPrefix` leaves `""`, and `subtle.ConstantTimeCompare` of two empty slices is 1. | `internal/httpapi/auth.go:17-18`. Not reachable from `main` today — config refuses an empty token (`internal/config/config.go:28`) — but the check guarding the ledger leans on that. |
| 2 | A token with surrounding whitespace is accepted raw. The phone trims its copy, so a token pasted into Koyeb with a trailing newline matches nothing: every push and snapshot answers 401, which the phone treats as silence. A whitespace-only token also passes the `== ""` check. | `config.go:22,28`; the phone trims in `SyncPrefs.kt` (`set(value) = … value.trim()`). |
| 3 | Nothing enforces the runbook's "32 random bytes". The 2A plan's local runs used `dev-token-not-secret` (20 characters). | `2026-09-05-phase2b-deploy.md` §3; `2026-09-05-phase2a-sync.md:1775`. |
| 4 | Local test runs wipe the dev server's database. `newTestStore` deletes every row from five tables in whatever `TEST_DATABASE_URL` names, and CLAUDE.md points tests and the dev server at the same `expense_garden`. CI uses its own `expense_garden_test`. | `internal/store/store_test.go:28-33`; `CLAUDE.md` backend commands; `.github/workflows/ci.yml:58`. The 2026-10-11 run cleared it; it now holds only test leftovers (one row each in `txn`, `game_event`, `category`). |

## Rulings

1. **Minimum token length: 32 characters, after trimming.** The runbook's
   `openssl rand -base64 32` gives 44; `openssl rand -hex 16` gives exactly 32; anything typed from
   memory falls short. *Cost:* a local run with a hand-typed token now fails at startup, with a
   message naming the command that makes one. The 2A plan's historical `dev-token-not-secret`
   commands no longer start the server.
2. **Config trims the token**, symmetric with the phone. Whitespace can't be part of a token on
   either side.
3. **The middleware fails closed on an empty server token**, although config already prevents
   one. The check that guards the snapshot endpoint shouldn't depend on a different package's
   invariant.
4. **Tests go through `Routes()` with a nil `Store`.** No database is needed, and a request that
   reaches a handler it shouldn't is reported as exactly that.
5. **The positive path is proven on push with a malformed body.** `400 "malformed body"` is the
   push handler's own answer, so getting it proves auth passed. Snapshot's positive path, which
   needs a real store, is proven end to end in Task 3 against local Postgres.
6. **Characterization tests are made to fail once.** The wrong-credential and right-token tests
   pin behaviour that is already correct, so they pass on their first run. Each is shown to catch a
   regression by a deliberate one-line mutation of `auth.go`, after which the file is restored from
   git.
7. **Deliberately unchanged:**
   - `ConstantTimeCompare` returns early on a length mismatch, which leaks the token's length.
     With 32+ random characters, knowing the length doesn't help anyone guess it.
   - Lowercase `bearer` is refused. RFC 7235 makes schemes case-insensitive, but the only client
     sends `Bearer`.
   - `/v1/ready` stays unauthenticated, as designed (`router.go:30-33`).
8. **Tests get their own local database, `expense_garden_test`, matching CI**, and CLAUDE.md's
   test command points there.
9. **Out of scope:** the rest of 2B (the runbook), TLS (Koyeb terminates it), rate limiting (one
   user, and a 44-character random token isn't brute-forceable), token rotation.

## Conventions

- Run every `go` command from `backend/core-api`. `$S` is the session scratchpad directory.
- Tests that need Postgres use
  `TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable"`.
  Never point it at `expense_garden`.
- CI's gate is `go vet ./...`, `gofmt -l .`, `go test ./... -count=1`, and the release build.
  Task 3 runs all four.
- Stage named paths only, plain commit messages, no attribution lines. Never push unless asked.
- Never print the token. It lives in a mode-600 scratch file and is read only inside commands.

## File map

| Task | Create | Modify |
|---|---|---|
| 0 | the local database `expense_garden_test` | — |
| 1 | `internal/httpapi/auth_test.go` | `internal/httpapi/auth.go` |
| 2 | `internal/config/config_test.go` | `internal/config/config.go` |
| 3 | — | `CLAUDE.md` (backend commands) |

**Done when:** all 17 Go tests pass (8 existing, 4 auth, 5 config), CI's four checks pass locally,
and a local server proves the curl matrix in Task 3 Step 3.

---

## Task 0: A database the tests are allowed to wipe

- [ ] **Step 1: Create it.**

```bash
createdb -h localhost expense_garden_test
```

- [ ] **Step 2: Run the existing suite against it.**

```bash
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable" go test ./... -count=1 -v 2>&1 | grep -cE "^--- PASS"
```

  Expected: `8`, and no `--- SKIP` or `--- FAIL` lines in the full output.

---

## Task 1: The auth boundary, and failing closed

**Files:**
- Create: `backend/core-api/internal/httpapi/auth_test.go`
- Modify: `backend/core-api/internal/httpapi/auth.go`

- [ ] **Step 1: Write the tests.** Create `internal/httpapi/auth_test.go`:

```go
package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The shape `openssl rand -base64 32` produces (runbook §3): 44 characters.
const goodToken = "q5Zx2vS8nB1fK7mW3tJ9hL0cR6yA4uE8pG2dV5oN1iM="

var protected = []struct{ name, method, path string }{
	{"push", http.MethodPost, "/v1/sync/push"},
	{"snapshot", http.MethodGet, "/v1/sync/snapshot"},
}

// serve runs one request through the real router. Every server in this file has a nil Store,
// so a request that wrongly passes auth panics inside its handler; that is reported as what it
// is, rather than as a crash.
func serve(t *testing.T, h http.Handler, req *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	defer func() {
		if p := recover(); p != nil {
			t.Fatalf("%s %s reached its handler, so auth let it through (panic on the nil store: %v)",
				req.Method, req.URL.Path, p)
		}
	}()
	h.ServeHTTP(rec, req)
	return rec
}

func TestEveryWrongCredentialIsRefused(t *testing.T) {
	h := (&Server{Token: goodToken}).Routes()
	creds := []struct{ name, header string }{ // an empty header means none is sent
		{"no header", ""},
		{"wrong token", "Bearer " + strings.Repeat("x", len(goodToken))},
		{"token without the scheme", goodToken},
		{"empty bearer", "Bearer "},
		{"basic scheme", "Basic " + goodToken},
		{"token with a trailing space", "Bearer " + goodToken + " "},
		{"prefix of the token", "Bearer " + goodToken[:len(goodToken)-1]},
	}
	for _, r := range protected {
		t.Run(r.name, func(t *testing.T) {
			for _, c := range creds {
				t.Run(c.name, func(t *testing.T) {
					req := httptest.NewRequest(r.method, r.path, strings.NewReader("not json"))
					if c.header != "" {
						req.Header.Set("Authorization", c.header)
					}
					if rec := serve(t, h, req); rec.Code != http.StatusUnauthorized {
						t.Fatalf("want 401, got %d (%s)", rec.Code, strings.TrimSpace(rec.Body.String()))
					}
				})
			}
		})
	}
}

func TestTheRightTokenGetsThrough(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/v1/sync/push", strings.NewReader("not json"))
	req.Header.Set("Authorization", "Bearer "+goodToken)
	rec := serve(t, (&Server{Token: goodToken}).Routes(), req)
	// 400 "malformed body" is the push handler's own answer: getting it proves auth passed,
	// without a database behind the handler.
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("want 400 from the push handler, got %d (%s)", rec.Code, strings.TrimSpace(rec.Body.String()))
	}
}

func TestHealthNeedsNoToken(t *testing.T) {
	rec := serve(t, (&Server{Token: goodToken}).Routes(), httptest.NewRequest(http.MethodGet, "/v1/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("want 200, got %d", rec.Code)
	}
}

// Config refuses to start without a token, but the check guarding the whole ledger must not
// lean on that. ConstantTimeCompare of two empty slices is 1, so a server with no token
// accepted "Bearer " as a key.
func TestAnEmptyServerTokenAuthenticatesNothing(t *testing.T) {
	h := (&Server{Token: ""}).Routes()
	for _, r := range protected {
		t.Run(r.name, func(t *testing.T) {
			req := httptest.NewRequest(r.method, r.path, strings.NewReader("not json"))
			req.Header.Set("Authorization", "Bearer ")
			if rec := serve(t, h, req); rec.Code != http.StatusUnauthorized {
				t.Fatalf("want 401, got %d (%s)", rec.Code, strings.TrimSpace(rec.Body.String()))
			}
		})
	}
}
```

- [ ] **Step 2: Run them and watch the fail-closed test fail.**

```bash
go test ./internal/httpapi/ -count=1 -v 2>&1 | grep -E "^(=== RUN|--- |    --- |        --- )|want|reached|^(ok|FAIL)"
```

  Expected: `FAIL`. Only `TestAnEmptyServerTokenAuthenticatesNothing` fails, on both subtests:
  - `push`: `want 401, got 400 ({"error":"malformed body"})`
  - `snapshot`: `GET /v1/sync/snapshot reached its handler, so auth let it through (panic on the nil store: runtime error: invalid memory address or nil pointer dereference)`

  `TestEveryWrongCredentialIsRefused` (14 subtests), `TestTheRightTokenGetsThrough` and
  `TestHealthNeedsNoToken` pass. They pin behaviour that is already correct; Steps 3 and 4 prove
  they can fail.

- [ ] **Step 3: Prove the wrong-credential test catches an open door** (Ruling 6). Make the check
  always pass, run, then restore:

```bash
sed -i '' 's/token == header || subtle.ConstantTimeCompare(\[\]byte(token), \[\]byte(s.Token)) != 1/subtle.ConstantTimeCompare([]byte(token), []byte(s.Token)) == 2/' internal/httpapi/auth.go
```

```bash
go test ./internal/httpapi/ -run TestEveryWrongCredentialIsRefused -count=1 -vet=off -v 2>&1 | grep -cE "^        --- FAIL"
```

  Expected: `14`, meaning every wrong credential got through. Then restore and confirm:

```bash
git checkout -- internal/httpapi/auth.go && git diff --quiet internal/httpapi/auth.go && echo restored
```

- [ ] **Step 4: Prove the right-token test catches a locked door.** Make the check always refuse,
  run, then restore:

```bash
sed -i '' 's/\[\]byte(s.Token)) != 1/[]byte(s.Token)) != 2/' internal/httpapi/auth.go
```

```bash
go test ./internal/httpapi/ -run TestTheRightTokenGetsThrough -count=1 -vet=off -v 2>&1 | grep -E "^--- FAIL|want 400"
```

  Expected: `--- FAIL: TestTheRightTokenGetsThrough`, with
  `want 400 from the push handler, got 401`. Then restore with the Step 3 restore command, and
  expect `restored`.

- [ ] **Step 5: Fail closed.** In `auth.go`, replace the comment and condition (lines 9-18, from
  `// requireToken guards every route but health.` through the `if` line) with:

```go
// requireToken guards every route but the two health checks.
//
// subtle.ConstantTimeCompare rather than ==: string comparison short-circuits on the first
// differing byte, which leaks the token's prefix to anyone who can time responses. One user
// makes that unlikely to matter and free to prevent.
//
// An unset server token fails closed. Config refuses to start without one, but the check that
// guards the whole ledger should not lean on that: ConstantTimeCompare of two empty slices is 1,
// which would make "Bearer " a key.
func (s *Server) requireToken(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token := strings.TrimPrefix(header, "Bearer ")
		if s.Token == "" || token == header || subtle.ConstantTimeCompare([]byte(token), []byte(s.Token)) != 1 {
```

  (The old comment said "every route but health"; `/v1/ready` is unauthenticated too.)

- [ ] **Step 6: Run them and watch them pass.**

```bash
go test ./internal/httpapi/ -count=1 -v 2>&1 | grep -cE "^--- PASS"
```

  Expected: `4`, and `ok` for the package. Then `gofmt -l internal/httpapi/` must list nothing;
  if it lists a file, run `gofmt -w` on it (formatting only) and re-run this step.

- [ ] **Step 7: Commit** (from the repo root).

```bash
git add backend/core-api/internal/httpapi/auth.go backend/core-api/internal/httpapi/auth_test.go
git commit -m "fix: fail closed when the sync token is unset

ConstantTimeCompare of two empty slices is 1, so a server with no token
accepted \"Bearer \" as a key. Config refuses an empty token at startup, but
the check that guards the whole ledger no longer depends on it. Tests now
pin the auth boundary through the real router: every wrong credential is
refused on both protected routes, the right one gets through, and health
needs none."
```

---

## Task 2: Trim the token, and refuse a weak one

**Files:**
- Create: `backend/core-api/internal/config/config_test.go`
- Modify: `backend/core-api/internal/config/config.go`

- [ ] **Step 1: Write the tests.** Create `internal/config/config_test.go`:

```go
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
```

- [ ] **Step 2: Declare the new error, with no behaviour yet,** so the tests compile and fail on
  their assertions. In `config.go`, after `var ErrMissing = …` (line 17), add:

```go

var ErrWeakToken = errors.New("config: SYNC_TOKEN must be at least 32 characters; generate one with: openssl rand -base64 32")
```

- [ ] **Step 3: Run them and watch three fail.**

```bash
go test ./internal/config/ -count=1 -v 2>&1 | grep -E "^--- |want"
```

  Expected: `FAIL`, with exactly three failures:
  - `TestTheTokenIsTrimmed`: `want the trimmed token and no error, got "  q5Zx2vS8nB1fK7mW3tJ9hL0cR6yA4uE8pG2dV5oN1iM=\n", <nil>`
  - `TestABlankTokenIsMissing`: `want ErrMissing, got <nil>`
  - `TestAShortTokenIsRefused`: `want ErrWeakToken, got <nil>`

  `TestAGeneratedTokenIsAccepted` and `TestTheDatabaseURLIsRequired` pass; they pin existing
  behaviour.

- [ ] **Step 4: Implement.** `Load` is the last thing in `config.go`, so replace the whole file
  with:

```go
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
```

- [ ] **Step 5: Run them and watch them pass.**

```bash
go test ./internal/config/ -count=1 -v 2>&1 | grep -cE "^--- PASS"
```

  Expected: `5`, and `ok` for the package. Then `gofmt -l internal/config/` must list nothing;
  if it lists a file, run `gofmt -w` on it (formatting only) and re-run this step.

- [ ] **Step 6: Commit** (from the repo root).

```bash
git add backend/core-api/internal/config/config.go backend/core-api/internal/config/config_test.go
git commit -m "fix: trim the sync token and refuse weak ones

The phone trims its copy of the token, but the server read SYNC_TOKEN raw:
a token pasted with a trailing newline matched nothing, and every push
answered 401, which the phone treats as silence. A whitespace-only token
also passed the empty check. Config now trims the token, treats a blank one
as missing, and refuses anything under 32 characters with the command that
generates one."
```

---

## Task 3: Prove it the way CI and the phone will see it

- [ ] **Step 1: CI's gate, locally.**

```bash
go vet ./... && echo "vet: clean"
```

```bash
gofmt -l . ; echo "gofmt: (no files listed above means clean)"
```

```bash
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable" go test ./... -count=1 -v 2>&1 | awk '/^--- PASS/{p++} /^--- (FAIL|SKIP)/{f++} END{print p+0, "pass,", f+0, "fail or skip"}'
```

  Expected: `vet: clean`; no files listed by gofmt; and `17 pass, 0 fail or skip`.

```bash
CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o "$S/core-api-linux" ./cmd/api && echo "release build: ok"
```

  `$S` is the session scratchpad, so the binary does not land in the repo.

- [ ] **Step 2: A short token stops the server.**

```bash
DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable" SYNC_TOKEN=dev-token-not-secret go run ./cmd/api; echo "exit=$?"
```

  Expected: `startup: config: SYNC_TOKEN must be at least 32 characters; generate one with: openssl rand -base64 32`
  and a non-zero exit.

- [ ] **Step 3: A padded, generated token works end to end.** Generate a token into a 600 file
  and start the server with it wrapped in whitespace, as a careless paste would leave it, on port
  18080:

```bash
umask 077; openssl rand -base64 32 > "$S/sync-token"; wc -c < "$S/sync-token"
```

  Expected: `45` (44 characters plus the newline). Then run the server in the background:

```bash
DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable" SYNC_TOKEN="  $(cat "$S/sync-token")  " ADDR=:18080 go run ./cmd/api
```

  Once `core-api listening on :18080` appears, run the checks. They are separate lines because
  zsh does not word-split an unquoted variable, and the token is only ever read inside a command:

```bash
B=http://localhost:18080; T="$(cat "$S/sync-token")"
curl -s -w ' -> %{http_code}\n' "$B/v1/health"
curl -s -w ' -> %{http_code}\n' "$B/v1/ready"
curl -s -w ' -> %{http_code}\n' "$B/v1/sync/snapshot"
curl -s -w ' -> %{http_code}\n' -H "Authorization: Bearer nope" "$B/v1/sync/snapshot"
curl -s -o /dev/null -w 'snapshot, right token -> %{http_code}\n' -H "Authorization: Bearer $T" "$B/v1/sync/snapshot"
curl -s -H "Authorization: Bearer $T" "$B/v1/sync/snapshot" | python3 -c "import json,sys; print('snapshot keys:', sorted(json.load(sys.stdin)))"
curl -s -w ' -> %{http_code}\n' -H "Authorization: Bearer $T" -X POST -d 'not json' "$B/v1/sync/push"
```

  Expected, line by line:
  - `{"ok":true} -> 200`
  - `{"ready":true} -> 200`
  - `{"error":"unauthorized"} -> 401` (no header)
  - `{"error":"unauthorized"} -> 401` (wrong token)
  - `snapshot, right token -> 200`
  - `snapshot keys: ['budgets', 'categories', 'events', 'payees', 'txns']`
  - `{"error":"malformed body"} -> 400` (push, right token)

  The `right` lines pass even though the server was given the token with spaces around it: that
  is the trim working.

- [ ] **Step 4: Stop the server and delete the token.** Stop the background `go run` (Ctrl-C, or
  kill its process), then:

```bash
lsof -ti tcp:18080 | xargs kill 2>/dev/null; rm -f "$S/sync-token" "$S/core-api-linux"; echo cleaned
```

- [ ] **Step 5: Point CLAUDE.md's test command at the test database.** In `CLAUDE.md`, the backend
  block's last line,

```
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" go test ./...
```

  becomes:

```
# Tests DELETE every row in the database they point at: never the dev server's.
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable" go test ./...
```

  And `SYNC_TOKEN=<generated> go run ./cmd/api` becomes
  `SYNC_TOKEN="$(openssl rand -base64 32)" go run ./cmd/api`, because a hand-typed token is now
  refused.

- [ ] **Step 6: Commit** (from the repo root).

```bash
git add CLAUDE.md
git commit -m "docs: point backend tests at their own database

newTestStore deletes every row in the database it is given, and the
documented command pointed it at the dev server's. Tests now use
expense_garden_test, as CI does, and the run command generates a token the
server will accept."
```

- [ ] **Step 7: Report.** Give the Task 1 and Task 2 failing and passing output, the curl matrix, and the
  commits. Do not push unless asked.
