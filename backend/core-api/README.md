# core-api

Phase 2A sync replica. Room on the phone is the source of truth; this is a backup target.

## Run locally

```bash
export DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable"
export SYNC_TOKEN=<a token you generate>
go run ./cmd/api
```

Migrations apply on boot and are a no-op when already applied.

## Test

```bash
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" go test ./...
```

The store tests need a real Postgres: last-write-wins lives in `ON CONFLICT` clauses, and a
mock would only prove the mock agrees with itself. They `DELETE FROM` every table on setup,
so point `TEST_DATABASE_URL` at a scratch database, never a real one.

## Endpoints

| Route | Auth | Purpose |
|---|---|---|
| `GET /v1/health` | none | liveness |
| `POST /v1/sync/push` | bearer | idempotent upsert of a change batch |
| `GET /v1/sync/snapshot` | bearer | full download, for restore |
