package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
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
		fx.Provide(func() *approvalGate {
			gate := newApprovalGate(envDuration("APPROVAL_TTL", 2*time.Minute))
			return gate
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

	if err := rt.StartStandalone(strat, *configDir, *wispYml); err != nil {
		log.Fatalf("StartStandalone: %v", err)
	}
	log.Println("AI trader strategy started; waiting for shutdown")
	if err := rt.Wait(); err != nil {
		log.Printf("wait: %v", err)
		os.Exit(1)
	}
}
