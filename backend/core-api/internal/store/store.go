// Package store is every SQL statement core-api runs.
//
// The last-write-wins rule lives in the ON CONFLICT clauses rather than in Go. That is
// deliberate: doing it in Go would mean reading each row, deciding, then writing — three
// round trips and a race between them. In SQL it is one atomic statement, and the rule is
// exactly the WHERE clause you can read here.
package store

import (
	"context"
	"crypto/sha256"
	"errors"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Store struct{ Pool *pgxpool.Pool }

// The JSON tags must match SyncClient's writers on the phone exactly. A mismatch decodes as
// a zero value with no error, which is the quietest possible bug.

type Category struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	ParentID    *int64 `json:"parentId"`
	IsNecessity bool   `json:"isNecessity"`
	UpdatedAt   int64  `json:"updatedAt"`
}

type Payee struct {
	ID                int64   `json:"id"`
	Name              string  `json:"name"`
	VPA               *string `json:"vpa"`
	DefaultCategoryID *int64  `json:"defaultCategoryId"`
	UpdatedAt         int64   `json:"updatedAt"`
}

type Txn struct {
	UUID              string  `json:"uuid"`
	AmountPaise       int64   `json:"amountPaise"`
	PayeeID           int64   `json:"payeeId"`
	CategoryID        int64   `json:"categoryId"`
	Source            string  `json:"source"`
	Status            string  `json:"status"`
	Regret            string  `json:"regret"`
	BreachedAtLogging bool    `json:"breachedAtLogging"`
	Note              *string `json:"note"`
	OccurredAt        int64   `json:"occurredAt"`
	CreatedAt         int64   `json:"createdAt"`
	UpdatedAt         int64   `json:"updatedAt"`
}

type Budget struct {
	CategoryID  *int64 `json:"categoryId"`
	Month       string `json:"month"`
	AmountPaise int64  `json:"amountPaise"`
	UpdatedAt   int64  `json:"updatedAt"`
}

type Event struct {
	ID              int64   `json:"id"`
	Type            string  `json:"type"`
	PayloadJSON     string  `json:"payloadJson"`
	TransactionUUID *string `json:"transactionUuid"`
	CreatedAt       int64   `json:"createdAt"`
}

type Tombstone struct {
	TableName string `json:"tableName"`
	RowKey    string `json:"rowKey"`
	DeletedAt int64  `json:"deletedAt"`
}

type Batch struct {
	Categories []Category
	Payees     []Payee
	Txns       []Txn
	Budgets    []Budget
	Tombstones []Tombstone
	Events     []Event
}

// ApplyBatch writes an entire push in one transaction, in foreign-key order. All or nothing:
// a partial apply would leave the phone's cursor advanced past rows the server never stored.
func (s *Store) ApplyBatch(ctx context.Context, userID int64, b Batch) error {
	return pgx.BeginFunc(ctx, s.Pool, func(tx pgx.Tx) error {
		for _, c := range b.Categories {
			if _, err := tx.Exec(ctx, `
				INSERT INTO category (user_id, id, name, parent_id, is_necessity, updated_at)
				VALUES ($1,$2,$3,$4,$5,$6)
				ON CONFLICT (user_id, id) DO UPDATE SET
					name = EXCLUDED.name, parent_id = EXCLUDED.parent_id,
					is_necessity = EXCLUDED.is_necessity, updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= category.updated_at`,
				userID, c.ID, c.Name, c.ParentID, c.IsNecessity, c.UpdatedAt); err != nil {
				return err
			}
		}
		for _, p := range b.Payees {
			if _, err := tx.Exec(ctx, `
				INSERT INTO payee (user_id, id, name, vpa, default_category_id, updated_at)
				VALUES ($1,$2,$3,$4,$5,$6)
				ON CONFLICT (user_id, id) DO UPDATE SET
					name = EXCLUDED.name, vpa = EXCLUDED.vpa,
					default_category_id = EXCLUDED.default_category_id,
					updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= payee.updated_at`,
				userID, p.ID, p.Name, p.VPA, p.DefaultCategoryID, p.UpdatedAt); err != nil {
				return err
			}
		}
		for _, t := range b.Txns {
			if _, err := tx.Exec(ctx, `
				INSERT INTO txn (user_id, uuid, amount_paise, payee_id, category_id, source, status,
					regret, breached_at_logging, note, occurred_at, created_at, updated_at)
				VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
				ON CONFLICT (user_id, uuid) DO UPDATE SET
					amount_paise = EXCLUDED.amount_paise, payee_id = EXCLUDED.payee_id,
					category_id = EXCLUDED.category_id, source = EXCLUDED.source,
					status = EXCLUDED.status, regret = EXCLUDED.regret,
					breached_at_logging = EXCLUDED.breached_at_logging, note = EXCLUDED.note,
					occurred_at = EXCLUDED.occurred_at, created_at = EXCLUDED.created_at,
					updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= txn.updated_at`,
				userID, t.UUID, t.AmountPaise, t.PayeeID, t.CategoryID, t.Source, t.Status, t.Regret,
				t.BreachedAtLogging, t.Note, t.OccurredAt, t.CreatedAt, t.UpdatedAt); err != nil {
				return err
			}
		}
		for _, bg := range b.Budgets {
			if _, err := tx.Exec(ctx, `
				INSERT INTO budget (user_id, category_id, month, amount_paise, updated_at)
				VALUES ($1,$2,$3,$4,$5)
				ON CONFLICT (user_id, category_id, month) DO UPDATE SET
					amount_paise = EXCLUDED.amount_paise, updated_at = EXCLUDED.updated_at
				WHERE EXCLUDED.updated_at >= budget.updated_at`,
				userID, bg.CategoryID, bg.Month, bg.AmountPaise, bg.UpdatedAt); err != nil {
				return err
			}
		}
		for _, e := range b.Events {
			// Append-only: re-sending an event is free, which is what makes blind retry safe.
			if _, err := tx.Exec(ctx, `
				INSERT INTO game_event (user_id, id, type, payload_json, transaction_uuid, created_at)
				VALUES ($1,$2,$3,$4,$5,$6)
				ON CONFLICT (user_id, id) DO NOTHING`,
				userID, e.ID, e.Type, e.PayloadJSON, e.TransactionUUID, e.CreatedAt); err != nil {
				return err
			}
		}
		for _, tomb := range b.Tombstones {
			if tomb.TableName != "budget" {
				continue // budget is the only synced table with deletes (spec §2.1)
			}
			catID, month, ok := ParseBudgetRowKey(tomb.RowKey)
			if !ok {
				continue
			}
			// Only if the row is not newer than the tombstone — otherwise a stale delete
			// would erase a budget the user has since re-set.
			if _, err := tx.Exec(ctx, `
				DELETE FROM budget
				WHERE user_id = $1 AND category_id IS NOT DISTINCT FROM $2 AND month = $3 AND updated_at <= $4`,
				userID, catID, month, tomb.DeletedAt); err != nil {
				return err
			}
		}
		return nil
	})
}

// ParseBudgetRowKey decodes "<categoryId or *>|<month>" (spec §2.1). The "*" sentinel means
// the overall budget, whose category_id is NULL.
func ParseBudgetRowKey(key string) (*int64, string, bool) {
	i := strings.IndexByte(key, '|')
	if i < 0 {
		return nil, "", false
	}
	head, month := key[:i], key[i+1:]
	if month == "" {
		return nil, "", false
	}
	if head == "*" {
		return nil, month, true
	}
	id, err := strconv.ParseInt(head, 10, 64)
	if err != nil {
		return nil, "", false
	}
	return &id, month, true
}

// Snapshot is everything one user has, for a restore. No pagination: a single user's lifetime ledger is a
// few thousand rows, and streaming it would buy complexity nobody needs.
func (s *Store) Snapshot(ctx context.Context, userID int64) (Batch, error) {
	var b Batch

	rows, err := s.Pool.Query(ctx, `SELECT id, name, parent_id, is_necessity, updated_at FROM category WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var c Category
		if err := rows.Scan(&c.ID, &c.Name, &c.ParentID, &c.IsNecessity, &c.UpdatedAt); err != nil {
			rows.Close()
			return b, err
		}
		b.Categories = append(b.Categories, c)
	}
	rows.Close()

	rows, err = s.Pool.Query(ctx, `SELECT id, name, vpa, default_category_id, updated_at FROM payee WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var p Payee
		if err := rows.Scan(&p.ID, &p.Name, &p.VPA, &p.DefaultCategoryID, &p.UpdatedAt); err != nil {
			rows.Close()
			return b, err
		}
		b.Payees = append(b.Payees, p)
	}
	rows.Close()

	rows, err = s.Pool.Query(ctx, `SELECT uuid, amount_paise, payee_id, category_id, source, status,
		regret, breached_at_logging, note, occurred_at, created_at, updated_at FROM txn WHERE user_id = $1 ORDER BY created_at`, userID)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var t Txn
		if err := rows.Scan(&t.UUID, &t.AmountPaise, &t.PayeeID, &t.CategoryID, &t.Source, &t.Status,
			&t.Regret, &t.BreachedAtLogging, &t.Note, &t.OccurredAt, &t.CreatedAt, &t.UpdatedAt); err != nil {
			rows.Close()
			return b, err
		}
		b.Txns = append(b.Txns, t)
	}
	rows.Close()

	rows, err = s.Pool.Query(ctx, `SELECT category_id, month, amount_paise, updated_at FROM budget WHERE user_id = $1 ORDER BY month`, userID)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var bg Budget
		if err := rows.Scan(&bg.CategoryID, &bg.Month, &bg.AmountPaise, &bg.UpdatedAt); err != nil {
			rows.Close()
			return b, err
		}
		b.Budgets = append(b.Budgets, bg)
	}
	rows.Close()

	// Ordered by id, which is the garden's replay order (parent spec §9.2).
	rows, err = s.Pool.Query(ctx, `SELECT id, type, payload_json, transaction_uuid, created_at FROM game_event WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var e Event
		if err := rows.Scan(&e.ID, &e.Type, &e.PayloadJSON, &e.TransactionUUID, &e.CreatedAt); err != nil {
			rows.Close()
			return b, err
		}
		b.Events = append(b.Events, e)
	}
	rows.Close()

	return b, rows.Err()
}

// ErrNoSuchUser is SetTokenHash's answer for a name that isn't there.
var ErrNoSuchUser = errors.New("store: no such user")

// CreateUser adds a user. A nil hash makes a user that exists but cannot sign in.
func (s *Store) CreateUser(ctx context.Context, name string, tokenHash []byte) (int64, error) {
	var id int64
	err := s.Pool.QueryRow(ctx,
		`INSERT INTO app_user (name, token_sha256, created_at)
		 VALUES ($1, $2, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT) RETURNING id`,
		name, tokenHash).Scan(&id)
	return id, err
}

// SetTokenHash replaces a user's token hash.
func (s *Store) SetTokenHash(ctx context.Context, name string, tokenHash []byte) error {
	tag, err := s.Pool.Exec(ctx, `UPDATE app_user SET token_sha256 = $2 WHERE name = $1`, name, tokenHash)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNoSuchUser
	}
	return nil
}

// UserByTokenHash finds the user a token hash belongs to; (0, false, nil) when none does.
func (s *Store) UserByTokenHash(ctx context.Context, h []byte) (int64, bool, error) {
	// An empty or nil hash never matches: `token_sha256 = NULL` is never true, and no stored
	// SHA-256 is empty.
	var id int64
	err := s.Pool.QueryRow(ctx, `SELECT id FROM app_user WHERE token_sha256 = $1`, h).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	return id, true, nil
}

// HashToken is what app_user stores for a token: SHA-256 of the trimmed token. The phone trims
// what is pasted, so the server must hash exactly what the phone sends.
func HashToken(token string) []byte {
	sum := sha256.Sum256([]byte(strings.TrimSpace(token)))
	return sum[:]
}

// UserByToken is UserByTokenHash on HashToken(token).
func (s *Store) UserByToken(ctx context.Context, token string) (int64, bool, error) {
	return s.UserByTokenHash(ctx, HashToken(token))
}
