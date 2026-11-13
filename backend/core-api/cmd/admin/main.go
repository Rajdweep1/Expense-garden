// Command admin manages core-api's users. It never prints a token: tokens go to 0600 files.
//
//	admin create-user <name> <token-file>
//	admin set-token   <name> <token-file>
//	admin import-fc   <name> <export.json>
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"sort"
	"time"

	"expensegarden/core-api/internal/admin"
	"expensegarden/core-api/internal/config"
	"expensegarden/core-api/internal/fcimport"
	"expensegarden/core-api/internal/migrations"
	"expensegarden/core-api/internal/store"

	"github.com/jackc/pgx/v5/pgxpool"
)

const usage = `usage:
  admin create-user <name> <token-file>
  admin set-token   <name> <token-file>
  admin import-fc   <name> <export.json>`

func main() {
	if len(os.Args) != 4 {
		log.Fatal(usage)
	}
	cmd, name, path := os.Args[1], os.Args[2], os.Args[3]

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("startup: %v", err)
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer pool.Close()
	if err := migrations.Apply(ctx, pool); err != nil {
		log.Fatalf("migrate: %v", err)
	}
	s := &store.Store{Pool: pool}

	switch cmd {
	case "create-user":
		err = admin.CreateUser(ctx, s, name, path)
	case "set-token":
		err = admin.SetToken(ctx, s, name, path)
	case "import-fc":
		err = importFC(ctx, s, name, path)
	default:
		log.Fatal(usage)
	}
	if err != nil {
		log.Fatalf("%s %s: %v", cmd, name, err)
	}
	if cmd != "import-fc" {
		fmt.Printf("%s %s: done; token written to %s\n", cmd, name, path)
	}
}

// importFC imports a Fortune City export under the named user and prints what it did, per
// category, so the result can be checked against FC's own totals.
func importFC(ctx context.Context, s *store.Store, name, path string) error {
	userID, err := s.UserIDByName(ctx, name)
	if err != nil {
		return fmt.Errorf("user %q: %w", name, err)
	}
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	entries, err := fcimport.Parse(f)
	if err != nil {
		return err
	}
	sum, err := fcimport.Import(ctx, s, userID, entries, time.Now().UnixMilli())
	if err != nil {
		return err
	}
	names := map[int64]string{}
	for _, c := range fcimport.Categories(0) {
		names[c.ID] = c.Name
	}
	ids := make([]int64, 0, len(sum.ByCategory))
	for id := range sum.ByCategory {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	var count int
	var paise int64
	for _, id := range ids {
		c := sum.ByCategory[id]
		count += c.Count
		paise += c.Paise
		fmt.Printf("%-16s %5d  ₹%d.%02d\n", names[id], c.Count, c.Paise/100, c.Paise%100)
	}
	fmt.Printf("%-16s %5d  ₹%d.%02d\n", "total", count, paise/100, paise%100)
	fmt.Printf("import-fc %s: %d new, %d already there\n", name, sum.Inserted, sum.Skipped)
	return nil
}
