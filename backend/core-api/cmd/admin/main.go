// Command admin manages core-api's users. It never prints a token: tokens go to 0600 files.
//
//	admin create-user <name> <token-file>
//	admin set-token   <name> <token-file>
package main

import (
	"context"
	"fmt"
	"log"
	"os"

	"expensegarden/core-api/internal/admin"
	"expensegarden/core-api/internal/config"
	"expensegarden/core-api/internal/migrations"
	"expensegarden/core-api/internal/store"

	"github.com/jackc/pgx/v5/pgxpool"
)

const usage = `usage:
  admin create-user <name> <token-file>
  admin set-token   <name> <token-file>`

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
	default:
		log.Fatal(usage)
	}
	if err != nil {
		log.Fatalf("%s %s: %v", cmd, name, err)
	}
	fmt.Printf("%s %s: done; token written to %s\n", cmd, name, path)
}
