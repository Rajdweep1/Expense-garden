package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

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

	api := &httpapi.Server{Token: cfg.Token, Store: &store.Store{Pool: pool}}
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	// Graceful shutdown. Container platforms send SIGTERM and then kill; without this an
	// in-flight push is severed mid-transaction. The phone would retry it — its cursor only
	// advances on a 2xx — but a half-applied batch is exactly the state ApplyBatch's single
	// transaction exists to prevent, so let it finish.
	go func() {
		stop := make(chan os.Signal, 1)
		signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
		<-stop
		log.Println("shutting down")
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			log.Printf("shutdown: %v", err)
		}
	}()

	log.Printf("core-api listening on %s", cfg.Addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("listen: %v", err)
	}
	log.Println("stopped cleanly")
}
