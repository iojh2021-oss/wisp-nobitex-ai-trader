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
//
// The AI provider and paper/live mode are resolved fresh on every cycle from
// the settings store (dashboard-editable, persisted in Postgres when
// configured), so changes made from the UI take effect on the next cycle
// without a redeploy. Environment variables remain the fallback.
type AITraderStrategy struct {
	strategy.BaseStrategy
	k        wisp.Wisp
	gate     *approvalGate
	settings *settingsStore
}

func NewAITraderStrategy(k wisp.Wisp, gate *approvalGate, settings *settingsStore) strategy.Strategy {
	s := &AITraderStrategy{k: k, gate: gate, settings: settings}
	s.BaseStrategy = *strategy.NewBaseStrategy(strategy.BaseStrategyConfig{Name: "nobitex-ai-trader"})
	return s
}

func (s *AITraderStrategy) Start(ctx context.Context) error {
	return s.StartWithRunner(ctx, s.run)
}

func envBool(name string, fallback bool) bool {
	v, ok := os.LookupEnv(name)
	if !ok {
		return fallback
	}
	v = strings.TrimSpace(v)
	return strings.EqualFold(v, "1") || strings.EqualFold(v, "true") || strings.EqualFold(v, "yes") || strings.EqualFold(v, "on")
}

func envFloat(name string, fallback float64) float64 {
	v, err := strconv.ParseFloat(os.Getenv(name), 64)
	if err != nil || v <= 0 {
		return fallback
	}
	return v
}

func envDuration(name string, fallback time.Duration) time.Duration {
	v := os.Getenv(name)
	if v == "" {
		return fallback
	}
	d, err := time.ParseDuration(v)
	if err != nil || d < time.Second {
		return fallback
	}
	return d
}

type aiDecider interface {
	decide(ctx context.Context, market, portfolio map[string]any) (tradeDecision, error)
}

// resolveConfig reads the current AI provider + paper/live mode, preferring
// the settings store (dashboard) and falling back to environment variables.
func (s *AITraderStrategy) resolveConfig() (aiDecider, bool) {
	provider := strings.ToLower(os.Getenv("AI_PROVIDER"))
	openaiKey := os.Getenv("OPENAI_API_KEY")
	openaiModel := os.Getenv("OPENAI_MODEL")
	if openaiModel == "" {
		openaiModel = "gpt-5-mini"
	}
	groqKey := os.Getenv("GROQ_API_KEY")
	groqModel := os.Getenv("GROQ_MODEL")
	if groqModel == "" {
		groqModel = "openai/gpt-oss-120b"
	}
	paper := envBool("PAPER_TRADING", true)

	if s.settings != nil {
		st := s.settings.get()
		provider = strings.ToLower(st.AIProvider)
		if st.OpenAIAPIKey != "" {
			openaiKey = st.OpenAIAPIKey
		}
		if st.OpenAIModel != "" {
			openaiModel = st.OpenAIModel
		}
		if st.GroqAPIKey != "" {
			groqKey = st.GroqAPIKey
		}
		if st.GroqModel != "" {
			groqModel = st.GroqModel
		}
		paper = st.PaperTrading
	}

	var ai aiDecider
	if provider == "groq" {
		ai = newGroqClient(groqKey, groqModel)
	} else {
		ai = newOpenAIClient(openaiKey, openaiModel)
	}
	return ai, paper
}

func (s *AITraderStrategy) run(ctx context.Context) {
	baseURL := strings.TrimRight(os.Getenv("NOBITEX_BASE_URL"), "/")
	if baseURL == "" {
		baseURL = "https://apiv2.nobitex.ir"
	}
	market := strings.ToUpper(os.Getenv("NOBITEX_MARKET"))
	if market == "" {
		market = "BTCIRT"
	}

	src, dst := splitMarket(market)
	nobitex := newNobitexClient(baseURL, "")
	risk := &riskGate{maxTradeQuote: envFloat("MAX_TRADE_QUOTE", 1000000), maxDailyLossQuote: envFloat("MAX_DAILY_LOSS_QUOTE", 2000000)}
	interval := envDuration("TRADING_LOOP_INTERVAL", 30*time.Second)
	minConfidence := envFloat("MIN_CONFIDENCE", 0.70)

	s.k.Log().Info("nobitex-ai-trader: Wisp runtime started market=%s approval=enabled", market)
	for {
		ai, paper := s.resolveConfig()
		if err := s.tick(ctx, nobitex, ai, risk, src, dst, market, paper, minConfidence); err != nil {
			s.k.Log().Failed("nobitex-ai-trader", market, "trading cycle failed", "error", err.Error())
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
		if strings.HasSuffix(m, quote) && len(m) > len(quote) {
			return m[:len(m)-len(quote)], quote
		}
	}
	return m, "irt"
}

func (s *AITraderStrategy) tick(ctx context.Context, n *nobitexClient, ai aiDecider, risk *riskGate, src, dst, market string, paper bool, minConfidence float64) error {
	stats, err := n.marketStats(ctx, src, dst)
	if err != nil {
		return fmt.Errorf("market stats: %w", err)
	}
	book, err := n.orderBook(ctx, market)
	if err != nil {
		return fmt.Errorf("orderbook: %w", err)
	}
	portfolio := map[string]any{"mode": "paper", "note": "No live balances or orders are used by this runtime."}
	decision, err := ai.decide(ctx, map[string]any{"market": market, "stats": stats, "orderbook": book}, portfolio)
	if err != nil {
		return err
	}
	if decision.Confidence < minConfidence {
		s.k.Log().Info("decision rejected: confidence %.3f < %.3f", decision.Confidence, minConfidence)
		return nil
	}
	if err := risk.validate(decision.Action, decision.QuoteAmount); err != nil {
		return err
	}
	if decision.Action == "hold" {
		s.k.Log().Info("AI decision: hold")
		return nil
	}
	if s.gate == nil {
		return fmt.Errorf("approval gate is not configured")
	}
	proposal, err := s.gate.create(market, decision)
	if err != nil {
		return fmt.Errorf("create approval proposal: %w", err)
	}
	s.k.Log().Info("TRADE PROPOSAL created id=%s action=%s market=%s quote=%.2f confidence=%.3f reason=%s", proposal.ID, proposal.Action, proposal.Market, proposal.QuoteAmount, proposal.Confidence, proposal.Reason)

	if paper {
		approved, err := s.gate.approve(proposal.ID)
		if err != nil {
			return fmt.Errorf("auto-approve paper: %w", err)
		}
		s.k.Log().Info("paper order auto-executed id=%s status=%s", approved.ID, approved.Status)
		return nil
	}

	approved, err := s.gate.approveLive(proposal.ID, "")
	if err != nil {
		return fmt.Errorf("auto-approve live: %w", err)
	}
	s.k.Log().Info("LIVE order auto-executed id=%s status=%s", approved.ID, approved.Status)
	return nil
}
