-- Users. Until now one shared token stood for one ledger; from here every row belongs to a user,
-- and every key and foreign key carries user_id, because the phone assigns ids itself and every
-- phone assigns the same ones (seed categories 1–602, payee and event ids from SQLite rowids).

CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT   NOT NULL UNIQUE,
    -- SHA-256 of the bearer token. A random 32-byte token can't be brute-forced, so a slow KDF
    -- buys nothing. NULL = the user exists but cannot sign in.
    token_sha256 BYTEA  UNIQUE,
    created_at   BIGINT NOT NULL
);

-- Everything already here was written by the single-token server: it is test data.
INSERT INTO app_user (name, created_at)
VALUES ('test', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT);

ALTER TABLE category   ADD COLUMN user_id BIGINT REFERENCES app_user(id);
ALTER TABLE payee      ADD COLUMN user_id BIGINT REFERENCES app_user(id);
ALTER TABLE txn        ADD COLUMN user_id BIGINT REFERENCES app_user(id);
ALTER TABLE budget     ADD COLUMN user_id BIGINT REFERENCES app_user(id);
ALTER TABLE game_event ADD COLUMN user_id BIGINT REFERENCES app_user(id);

UPDATE category   SET user_id = (SELECT id FROM app_user WHERE name = 'test');
UPDATE payee      SET user_id = (SELECT id FROM app_user WHERE name = 'test');
UPDATE txn        SET user_id = (SELECT id FROM app_user WHERE name = 'test');
UPDATE budget     SET user_id = (SELECT id FROM app_user WHERE name = 'test');
UPDATE game_event SET user_id = (SELECT id FROM app_user WHERE name = 'test');

ALTER TABLE category   ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE payee      ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE txn        ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE budget     ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE game_event ALTER COLUMN user_id SET NOT NULL;

-- Foreign keys before the keys they depend on.
ALTER TABLE category   DROP CONSTRAINT category_parent_id_fkey;
ALTER TABLE payee      DROP CONSTRAINT payee_default_category_id_fkey;
ALTER TABLE txn        DROP CONSTRAINT txn_category_id_fkey;
ALTER TABLE txn        DROP CONSTRAINT txn_payee_id_fkey;
ALTER TABLE budget     DROP CONSTRAINT budget_category_id_fkey;
ALTER TABLE game_event DROP CONSTRAINT game_event_transaction_uuid_fkey;

ALTER TABLE category   DROP CONSTRAINT category_pkey;
ALTER TABLE payee      DROP CONSTRAINT payee_pkey;
ALTER TABLE payee      DROP CONSTRAINT payee_vpa_key;
ALTER TABLE txn        DROP CONSTRAINT txn_pkey;
ALTER TABLE budget     DROP CONSTRAINT budget_category_id_month_key;
ALTER TABLE game_event DROP CONSTRAINT game_event_pkey;
DROP INDEX idx_txn_occurred_at;
DROP INDEX idx_game_event_txn;

ALTER TABLE category   ADD PRIMARY KEY (user_id, id);
ALTER TABLE payee      ADD PRIMARY KEY (user_id, id);
ALTER TABLE payee      ADD UNIQUE (user_id, vpa);
ALTER TABLE txn        ADD PRIMARY KEY (user_id, uuid);
-- NULLS NOT DISTINCT still load-bearing (001): category_id is NULL for the overall budget.
ALTER TABLE budget     ADD UNIQUE NULLS NOT DISTINCT (user_id, category_id, month);
ALTER TABLE game_event ADD PRIMARY KEY (user_id, id);

-- With user_id in every foreign key, one user's row can never point at another's. The column
-- list on SET NULL keeps user_id itself from being nulled (Postgres ≥ 15). MATCH SIMPLE (the
-- default) lets a NULL parent, default category, budget category or event txn through.
ALTER TABLE category ADD FOREIGN KEY (user_id, parent_id) REFERENCES category (user_id, id);
ALTER TABLE payee ADD FOREIGN KEY (user_id, default_category_id) REFERENCES category (user_id, id)
    ON DELETE SET NULL (default_category_id);
ALTER TABLE txn ADD FOREIGN KEY (user_id, payee_id) REFERENCES payee (user_id, id);
ALTER TABLE txn ADD FOREIGN KEY (user_id, category_id) REFERENCES category (user_id, id);
ALTER TABLE budget ADD FOREIGN KEY (user_id, category_id) REFERENCES category (user_id, id)
    ON DELETE CASCADE;
ALTER TABLE game_event ADD FOREIGN KEY (user_id, transaction_uuid) REFERENCES txn (user_id, uuid)
    ON DELETE SET NULL (transaction_uuid);

CREATE INDEX idx_txn_occurred_at ON txn (user_id, occurred_at);
CREATE INDEX idx_game_event_txn ON game_event (user_id, transaction_uuid);
