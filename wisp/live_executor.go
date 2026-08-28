package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sync"
	"time"
)

const nobitexOrderURL = "https://apiv2.nobitex.ir/market/orders/add"

// LiveExecution places a REAL order using real money. Gated by:
// 1) LIVE_TRADING_ENABLED=true env var, 2) a confirmation phrase typed
// by the user, 3) the same riskGate used for paper trading.
type LiveExecution struct {
	ID          string    `json:"id"`
	ProposalID  string    `json:"proposal_id"`
	Market      string    `json:"market"`
	Action      string    `json:"action"`
	QuoteAmount float64   `json:"quote_amount"`
	Status      string    `json:"status"`
	ExecutedAt  time.Time `json:"executed_at"`
	NobitexRef  string    `json:"nobitex_ref"`
}

type liveExecutor struct {
	mu         sync.Mutex
	executions map[string]LiveExecution
	risk       *riskGate
	httpClient *http.Client
	apiToken   string
	enabled    bool
}

func newLiveExecutor(risk *riskGate) *liveExecutor {
	return &liveExecutor{
		executions: make(map[string]LiveExecution),
		risk:       risk,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		apiToken:   os.Getenv("NOBITEX_API_TOKEN"),
		enabled:    os.Getenv("LIVE_TRADING_ENABLED") == "true",
	}
}

// newLiveExecutorFromEnv builds a live executor with its own dedicated risk
// gate, configured from MAX_TRADE_QUOTE_LIVE / MAX_DAILY_LOSS_LIVE. This is
// intentionally separate from the paper-trading risk gate so tightening or
// loosening one never silently affects the other.
func newLiveExecutorFromEnv() *liveExecutor {
	maxTrade := envFloat("MAX_TRADE_QUOTE_LIVE", 0)
	maxDailyLoss := envFloat("MAX_DAILY_LOSS_LIVE", 0)
	risk := &riskGate{maxTradeQuote: maxTrade, maxDailyLossQuote: maxDailyLoss}
	return newLiveExecutor(risk)
}


func (e *liveExecutor) execute(p TradeProposal, confirmPhrase string) (LiveExecution, error) {
	if !e.enabled {
		return LiveExecution{}, fmt.Errorf("live trading is disabled (set LIVE_TRADING_ENABLED=true)")
	}
	if e.apiToken == "" {
		return LiveExecution{}, fmt.Errorf("NOBITEX_API_TOKEN is not configured")
	}
	expected := fmt.Sprintf("CONFIRM LIVE %s", p.Market)
	if confirmPhrase != expected {
		return LiveExecution{}, fmt.Errorf("confirmation phrase mismatch; expected %q", expected)
	}
	if err := e.risk.validate(p.Action, p.QuoteAmount); err != nil {
		return LiveExecution{}, fmt.Errorf("risk gate rejected order: %w", err)
	}
	if p.Action != "buy" && p.Action != "sell" {
		return LiveExecution{}, fmt.Errorf("cannot execute action %q live", p.Action)
	}

	src, dst := splitMarket(p.Market)

	reqBody, _ := json.Marshal(map[string]string{
		"type":          p.Action,
		"srcCurrency":   src,
		"dstCurrency":   dst,
		"amount":        fmt.Sprintf("%.8f", p.QuoteAmount),
		"clientOrderId": p.ID,
	})

	req, err := http.NewRequest(http.MethodPost, nobitexOrderURL, bytes.NewReader(reqBody))
	if err != nil {
		return LiveExecution{}, err
	}
	req.Header.Set("Authorization", "Token "+e.apiToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := e.httpClient.Do(req)
	if err != nil {
		return LiveExecution{}, fmt.Errorf("nobitex request failed: %w", err)
	}
	defer resp.Body.Close()

	var result struct {
		Status  string `json:"status"`
		OrderID int    `json:"orderId"`
		Message string `json:"message"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return LiveExecution{}, fmt.Errorf("could not parse nobitex response: %w", err)
	}
	if result.Status != "ok" {
		return LiveExecution{}, fmt.Errorf("nobitex rejected order: %s", result.Message)
	}

	now := time.Now().UTC()
	id, err := newProposalID()
	if err != nil {
		return LiveExecution{}, err
	}
	x := LiveExecution{
		ID: id, ProposalID: p.ID, Market: p.Market, Action: p.Action,
		QuoteAmount: p.QuoteAmount, Status: "filled", ExecutedAt: now,
		NobitexRef: fmt.Sprintf("NOBITEX-%d", result.OrderID),
	}

	e.mu.Lock()
	defer e.mu.Unlock()
	if _, exists := e.executions[p.ID]; exists {
		return LiveExecution{}, fmt.Errorf("proposal already executed")
	}
	e.executions[p.ID] = x
	return x, nil
}

func (e *liveExecutor) list() []LiveExecution {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := make([]LiveExecution, 0, len(e.executions))
	for _, x := range e.executions {
		out = append(out, x)
	}
	return out
}

