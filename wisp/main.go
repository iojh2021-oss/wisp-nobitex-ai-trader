package main

import (
    "context"
    "flag"
    "log"
    "os"

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

    app := fx.New(
        wisp.Module,
        fx.Provide(NewAITraderStrategy),
        fx.Populate(&rt, &strat),
        fx.NopLogger,
    )
    if err := app.Start(ctx); err != nil {
        log.Fatalf("fx start: %v", err)
    }
    defer func() { _ = app.Stop(ctx) }()

    if err := rt.StartStandalone(strat, *configDir, *wispYml); err != nil {
        log.Fatalf("StartStandalone: %v", err)
    }
    log.Println("AI trader strategy started; waiting for shutdown")
    if err := rt.Wait(); err != nil {
        log.Printf("wait: %v", err)
        os.Exit(1)
    }
}
