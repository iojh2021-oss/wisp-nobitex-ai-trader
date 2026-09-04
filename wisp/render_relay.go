package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

type renderRelayOrderRequest struct {
	ProposalID  string  `json:"proposal_id"`
	Market      string  `json:"market"`
	Action      string  `json:"action"`
	QuoteAmount float64 `json:"quote_amount"`
}

type renderRelayOrderResponse struct {
	OrderID int64  `json:"order_id"`
	Status  string `json:"status"`
	Symbol  string `json:"symbol"`
}

func (e *sandboxExecutor) executeViaRender(p TradeProposal) (SandboxExecution, error) {
	baseURL := strings.TrimRight(strings.TrimSpace(os.Getenv("RENDER_RELAY_URL")), "/")
	token := strings.TrimSpace(os.Getenv("RENDER_RELAY_TOKEN"))
	if baseURL == "" || token == "" {
		return SandboxExecution{}, fmt.Errorf("Render relay is enabled but RENDER_RELAY_URL / RENDER_RELAY_TOKEN are not configured")
	}

	payload, err := json.Marshal(renderRelayOrderRequest{
		ProposalID: p.ID, Market: p.Market, Action: p.Action, QuoteAmount: p.QuoteAmount,
	})
	if err != nil {
		return SandboxExecution{}, fmt.Errorf("encode Render relay request: %w", err)
	}

	req, err := http.NewRequest(http.MethodPost, baseURL+"/relay/binance/order", bytes.NewReader(payload))
	if err != nil {
		return SandboxExecution{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Wisp-Relay-Token", token)

	resp, err := e.httpClient.Do(req)
	if err != nil {
		return SandboxExecution{}, fmt.Errorf("Render relay request failed: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return SandboxExecution{}, fmt.Errorf("Render relay rejected sandbox order: %s", strings.TrimSpace(string(raw)))
	}

	var result renderRelayOrderResponse
	if err := json.Unmarshal(raw, &result); err != nil {
		return SandboxExecution{}, fmt.Errorf("decode Render relay response: %w", err)
	}
	if result.OrderID == 0 {
		return SandboxExecution{}, fmt.Errorf("Render relay returned no Binance order id")
	}

	now := time.Now().UTC()
	id, err := newProposalID()
	if err != nil {
		return SandboxExecution{}, err
	}
	symbol := result.Symbol
	if symbol == "" {
		symbol = strings.ToUpper(strings.TrimSpace(os.Getenv("SANDBOX_MARKET")))
		if symbol == "" {
			symbol = "BTCUSDT"
		}
	}
	status := result.Status
	if status == "" {
		status = "accepted"
	}
	x := SandboxExecution{
		ID: id, ProposalID: p.ID, Market: symbol, Action: p.Action,
		QuoteAmount: p.QuoteAmount, Status: status, ExecutedAt: now,
		BinanceRef: fmt.Sprintf("BINANCE-TESTNET-%d", result.OrderID),
		Executor: "render",
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, exists := e.executions[p.ID]; exists {
		return SandboxExecution{}, fmt.Errorf("proposal already executed")
	}
	e.executions[p.ID] = x
	return x, nil
}

func renderRelayOrderHandler(w http.ResponseWriter, r *http.Request) {
	if !envBool("RENDER_EXECUTOR_ONLY", false) {
		http.Error(w, "render relay disabled", http.StatusNotFound)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	expected := strings.TrimSpace(os.Getenv("RENDER_RELAY_TOKEN"))
	if expected == "" || r.Header.Get("X-Wisp-Relay-Token") != expected {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var in renderRelayOrderRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}
	in.Action = strings.ToLower(strings.TrimSpace(in.Action))
	in.Market = strings.ToUpper(strings.TrimSpace(in.Market))
	if in.Action != "buy" && in.Action != "sell" {
		http.Error(w, "invalid action", http.StatusBadRequest)
		return
	}
	if in.ProposalID == "" || in.QuoteAmount <= 0 {
		http.Error(w, "invalid order", http.StatusBadRequest)
		return
	}

	apiKey := strings.TrimSpace(os.Getenv("BINANCE_TESTNET_API_KEY"))
	apiSecret := strings.TrimSpace(os.Getenv("BINANCE_TESTNET_API_SECRET"))
	if apiKey == "" || apiSecret == "" {
		http.Error(w, "Binance testnet credentials are not configured", http.StatusServiceUnavailable)
		return
	}
	symbol := strings.ToUpper(strings.TrimSpace(os.Getenv("SANDBOX_MARKET")))
	if symbol == "" {
		symbol = "BTCUSDT"
	}
	side := "BUY"
	if in.Action == "sell" {
		side = "SELL"
	}

	orderID, status, err := placeBinanceTestnetOrder(&http.Client{Timeout: 10 * time.Second},
		TradeProposal{ID: in.ProposalID, Market: in.Market, Action: in.Action, QuoteAmount: in.QuoteAmount},
		apiKey, apiSecret, symbol, side)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, renderRelayOrderResponse{OrderID: orderID, Status: status, Symbol: symbol})
}
