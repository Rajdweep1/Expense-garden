package main

import (
	"context"
	"log"
	"net/http"

	"expensegarden/core-api/internal/config"
	"expensegarden/core-api/internal/httpapi"
	"expensegarden/core-api/internal/migrations"
	"expensegarden/core-api/internal/store"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("startup: %v", err)
	}

	pool, err := pgxpool.New(context.Background(), cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer pool.Close()

	// Migrations run on every boot and are a no-op when already applied, so a fresh deploy
	// and a restart follow exactly the same path.
	if err := migrations.Apply(context.Background(), pool); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	srv := &httpapi.Server{Token: cfg.Token, Store: &store.Store{Pool: pool}}
	log.Printf("core-api listening on %s", cfg.Addr)
	if err := http.ListenAndServe(cfg.Addr, srv.Routes()); err != nil {
		log.Fatalf("listen: %v", err)
	}
}
