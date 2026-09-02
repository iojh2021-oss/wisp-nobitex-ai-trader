package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/wisp-trading/sdk/pkg/types/runtime"
	"github.com/wisp-trading/sdk/pkg/types/strategy"
	"github.com/wisp-trading/sdk/wisp"
	"go.uber.org/fx"
)

func main() {
	configDir := flag.String("config", ".", "strategy config directory")
	wispYml := flag.String("wisp", "", "Wisp settings path")
	flag.Parse()

	ctx := context.Background()
	var rt runtime.Runtime
	var strat strategy.Strategy
	var gate *approvalGate

	app := fx.New(
		wisp.Module,
		fx.Provide(func() *paperExecutor { return newPaperExecutor() }),
		fx.Provide(func() *settingsStore { return newSettingsStore() }),
		fx.Provide(func(settings *settingsStore) *approvalGate {
			return newApprovalGate(envDuration("APPROVAL_TTL", 2*time.Minute), settings)
		}),
		fx.Invoke(func(gate *approvalGate, executor *paperExecutor) { gate.executor = executor }),
		fx.Provide(NewAITraderStrategy),
		fx.Populate(&rt, &strat, &gate),
		fx.NopLogger,
	)
	if err := app.Start(ctx); err != nil {
		log.Fatalf("fx start: %v", err)
	}
	defer func() { _ = app.Stop(ctx) }()

	approvalServer := gate.serve()
	go func() {
		log.Printf("approval dashboard/API listening on %s", approvalServer.Addr)
		if err := approvalServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("approval API: %v", err)
		}
	}()
	defer func() { _ = approvalServer.Shutdown(context.Background()) }()

	// NOTE: rt.StartStandalone() belongs to the generic wisp-trading/sdk
	// exchange-connector bootstrap (Hyperliquid-style exchanges registered
	// in ~/.wisp/connectors.yml). Our Nobitex integration is fully custom
	// and self-contained inside AITraderStrategy.run() — it never uses that
	// connector registry — so we start the strategy directly instead of
	// going through the generic exchange-config validation, which would
	// otherwise hard-fail with "exchanges is empty".
	_ = configDir
	_ = wispYml
	_ = rt
	if err := strat.Start(ctx); err != nil {
		log.Fatalf("strategy start: %v", err)
	}
	log.Println("AI trader strategy started (direct run, bypassing generic exchange bootstrap); waiting for shutdown")

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	<-sigCh
	log.Println("shutdown signal received")
}
