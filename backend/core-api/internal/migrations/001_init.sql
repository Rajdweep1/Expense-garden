-- Phase 2A. Mirrors Room with real foreign keys (parent spec §11).
-- Enums are TEXT with CHECK constraints rather than Postgres enum types: a CHECK is trivially
-- altered when a variant is added, an enum type is not.

CREATE TABLE IF NOT EXISTS category (
    id           BIGINT PRIMARY KEY,
    name         TEXT   NOT NULL,
    parent_id    BIGINT REFERENCES category(id),
    is_necessity BOOLEAN NOT NULL,
    updated_at   BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS payee (
    id                  BIGINT PRIMARY KEY,
    name                TEXT   NOT NULL,
    vpa                 TEXT   UNIQUE,
    default_category_id BIGINT REFERENCES category(id) ON DELETE SET NULL,
    updated_at          BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS txn (
    uuid                TEXT   PRIMARY KEY,
    amount_paise        BIGINT NOT NULL,
    payee_id            BIGINT NOT NULL REFERENCES payee(id),
    category_id         BIGINT NOT NULL REFERENCES category(id),
    source              TEXT   NOT NULL CHECK (source IN ('QR_GATE','MANUAL','IMPORT')),
    status              TEXT   NOT NULL CHECK (status IN ('PENDING_CONFIRM','LOGGED','DISCARDED')),
    regret              TEXT   NOT NULL CHECK (regret IN ('UNRATED','WORTH_IT','REGRET')),
    breached_at_logging BOOLEAN NOT NULL,
    note                TEXT,
    occurred_at         BIGINT NOT NULL,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL
);

-- NULLS NOT DISTINCT is load-bearing (spec §2.1): category_id is NULL for the overall budget,
-- and without this clause Postgres would treat every overall budget for a month as a distinct
-- row, so ON CONFLICT would never fire and duplicates would accumulate silently.
CREATE TABLE IF NOT EXISTS budget (
    category_id  BIGINT REFERENCES category(id) ON DELETE CASCADE,
    month        TEXT   NOT NULL,
    amount_paise BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL,
    UNIQUE NULLS NOT DISTINCT (category_id, month)
);

-- No CHECK on `type`. The server is a replica, not the authority on the game's vocabulary:
-- constraining it here would mean every new event type in a future phase silently fails to
-- sync until Postgres is migrated first.
CREATE TABLE IF NOT EXISTS game_event (
    id               BIGINT PRIMARY KEY,
    type             TEXT   NOT NULL,
    payload_json     TEXT   NOT NULL,
    transaction_uuid TEXT   REFERENCES txn(uuid) ON DELETE SET NULL,
    created_at       BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_txn_occurred_at ON txn(occurred_at);
CREATE INDEX IF NOT EXISTS idx_game_event_txn ON game_event(transaction_uuid);
