package main

import (
    "context"
    "os"
    "time"

    "github.com/wisp-trading/sdk/pkg/types/strategy"
    "github.com/wisp-trading/sdk/pkg/types/wisp"
)

// AITraderStrategy is the Wisp lifecycle boundary. Exchange-specific execution is
// deliberately kept behind the Nobitex adapter until credentials are supplied.
type AITraderStrategy struct {
    strategy.BaseStrategy
    k wisp.Wisp
}

func NewAITraderStrategy(k wisp.Wisp) strategy.Strategy {
    s := &AITraderStrategy{k: k}
    s.BaseStrategy = *strategy.NewBaseStrategy(strategy.BaseStrategyConfig{Name: "nobitex-ai-trader"})
    return s
}

func (s *AITraderStrategy) Start(ctx context.Context) error {
    return s.StartWithRunner(ctx, s.run)
}

func (s *AITraderStrategy) run(ctx context.Context) {
    interval := 30 * time.Second
    if os.Getenv("TRADING_LOOP_INTERVAL") != "" {
        // Keep parsing intentionally conservative; deployment can replace this
        // with a duration parser once the live connector is enabled.
    }
    ticker := time.NewTicker(interval)
    defer ticker.Stop()
    s.k.Log().Info("nobitex-ai-trader: paper-safe Wisp runtime started")
    for {
        select {
        case <-ctx.Done():
            s.k.Log().Info("nobitex-ai-trader: shutdown")
            return
        case <-ticker.C:
            s.k.Log().Info("nobitex-ai-trader: heartbeat")
        }
    }
}
