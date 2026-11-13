# core-api

Phase 2A sync replica. Room on the phone is the source of truth; this is a backup target.

## Run locally

```bash
export DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable"
go run ./cmd/api
```

Migrations apply on boot and are a no-op when already applied.

## Users

Every row belongs to a user (`app_user`); a request is that user's when its bearer token's
SHA-256 matches. Tokens never appear in config, stdout or logs:

```bash
go run ./cmd/admin create-user <name> <token-file>   # new user, token to a new 0600 file
go run ./cmd/admin set-token   <name> <token-file>   # fresh token; the old one stops working
```

Rows written before users existed (migration 002) belong to the user `test`, which has no token
until `set-token test` gives it one.

## Test

```bash
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable" go test -p 1 ./...
```

The store tests need a real Postgres: last-write-wins lives in `ON CONFLICT` clauses, and a
mock would only prove the mock agrees with itself. They `DELETE FROM` every table on setup,
so point `TEST_DATABASE_URL` at a scratch database, never a real one. `-p 1` because several
packages share that database.

## Endpoints

| Route | Auth | Purpose |
|---|---|---|
| `GET /v1/health` | none | liveness |
| `POST /v1/sync/push` | bearer (a user's token) | idempotent upsert of a change batch, into that user's rows |
| `GET /v1/sync/snapshot` | bearer (a user's token) | that user's full ledger, for restore |
