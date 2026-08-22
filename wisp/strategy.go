package main

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/wisp-trading/sdk/pkg/types/strategy"
	"github.com/wisp-trading/sdk/pkg/types/wisp"
)

// AITraderStrategy is the Wisp lifecycle boundary. Wisp owns the long-running
// loop while deterministic risk controls remain authoritative over the AI.
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

func envBool(name string, fallback bool) bool {
	v, ok := os.LookupEnv(name)
	if !ok { return fallback }
	return strings.EqualFold(strings.TrimSpace(v), "1") || strings.EqualFold(strings.TrimSpace(v), "true") || strings.EqualFold(strings.TrimSpace(v), "yes") || strings.EqualFold(strings.TrimSpace(v), "on")
}

func envFloat(name string, fallback float64) float64 {
	v, err := strconv.ParseFloat(os.Getenv(name), 64)
	if err != nil || v <= 0 { return fallback }
	return v
}

func envDuration(name string, fallback time.Duration) time.Duration {
	v := os.Getenv(name)
	if v == "" { return fallback }
	d, err := time.ParseDuration(v)
	if err != nil || d < time.Second { return fallback }
	return d
}

func (s *AITraderStrategy) run(ctx context.Context) {
	baseURL := strings.TrimRight(os.Getenv("NOBITEX_BASE_URL"), "/")
	if baseURL == "" { baseURL = "https://api.nobitex.ir" }
	market := strings.ToUpper(os.Getenv("NOBITEX_MARKET"))
	if market == "" { market = "BTCIRT" }
	model := os.Getenv("OPENAI_MODEL")
	if model == "" { model = "gpt-5-mini" }
	paper := envBool("PAPER_TRADING", true)
	live := envBool("LIVE_TRADING_ENABLED", false)
	if live && paper { s.k.Log().Error("live trading blocked: PAPER_TRADING=true") ; return }
	if live && os.Getenv("NOBITEX_TOKEN") == "" { s.k.Log().Error("live trading blocked: NOBITEX_TOKEN is missing") ; return }

	src, dst := splitMarket(market)
	nobitex := newNobitexClient(baseURL, os.Getenv("NOBITEX_TOKEN"))
	ai := newOpenAIClient(os.Getenv("OPENAI_API_KEY"), model)
	risk := &riskGate{maxTradeQuote: envFloat("MAX_TRADE_QUOTE", 1000000), maxDailyLossQuote: envFloat("MAX_DAILY_LOSS_QUOTE", 2000000)}
	interval := envDuration("TRADING_LOOP_INTERVAL", 30*time.Second)
	minConfidence := envFloat("MIN_CONFIDENCE", 0.70)

	s.k.Log().Info(fmt.Sprintf("nobitex-ai-trader: Wisp runtime started market=%s paper=%t live=%t", market, paper, live))
	for {
		if err := s.tick(ctx, nobitex, ai, risk, src, dst, market, paper, live, minConfidence); err != nil {
			s.k.Log().Error(fmt.Sprintf("trading cycle failed: %v", err))
		}
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			s.k.Log().Info("nobitex-ai-trader: shutdown")
			return
		case <-timer.C:
		}
	}
}

func splitMarket(market string) (string, string) {
	m := strings.ToLower(market)
	for _, quote := range []string{"irt", "usdt", "usdc"} {
		if strings.HasSuffix(m, quote) && len(m) > len(quote) { return m[:len(m)-len(quote)], quote }
	}
	return m, "irt"
}

func (s *AITraderStrategy) tick(ctx context.Context, n *nobitexClient, ai *openAIClient, risk *riskGate, src, dst, market string, paper, live bool, minConfidence float64) error {
	stats, err := n.marketStats(ctx, src, dst)
	if err != nil { return fmt.Errorf("market stats: %w", err) }
	book, err := n.orderBook(ctx, market)
	if err != nil { return fmt.Errorf("orderbook: %w", err) }
	portfolio := map[string]any{"mode": "paper", "note": "Balances are simulated; do not infer live balances."}
	if live {
		wallet, err := n.wallet(ctx)
		if err != nil { return fmt.Errorf("wallet: %w", err) }
		portfolio = wallet
	}
	decision, err := ai.decide(ctx, map[string]any{"market": market, "stats": stats, "orderbook": book}, portfolio)
	if err != nil { return err }
	if decision.Confidence < minConfidence { s.k.Log().Info(fmt.Sprintf("decision rejected: confidence %.3f < %.3f", decision.Confidence, minConfidence)); return nil }
	if err := risk.validate(decision.Action, decision.QuoteAmount); err != nil { return err }
	if decision.Action == "hold" { s.k.Log().Info("AI decision: hold"); return nil }
	if paper {
		s.k.Log().Info(fmt.Sprintf("PAPER order: %s %s quote=%.2f confidence=%.3f reason=%s", decision.Action, market, decision.QuoteAmount, decision.Confidence, decision.Reason))
		return nil
	}
	payload := map[string]any{
		"type": decision.Action,
		"srcCurrency": src,
		"dstCurrency": dst,
		"amount": decision.QuoteAmount,
	}
	result, err := n.addOrder(ctx, payload)
	if err != nil { return fmt.Errorf("live order: %w", err) }
	s.k.Log().Info(fmt.Sprintf("LIVE order accepted: %v", result))
	return nil
}
