package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const binanceTestnetBase = "https://testnet.binance.vision"

// SandboxExecution places a MARKET order on the Binance Spot Test Network
// (fake funds, real matching engine). Used to test order-execution mechanics
// safely; the AI's trading decisions themselves are still based on real
// Nobitex market data.
type SandboxExecution struct {
	ID          string    `json:"id"`
	ProposalID  string    `json:"proposal_id"`
	Market      string    `json:"market"`
	Action      string    `json:"action"`
	QuoteAmount float64   `json:"quote_amount"`
	Status      string    `json:"status"`
	ExecutedAt  time.Time `json:"executed_at"`
	BinanceRef  string    `json:"binance_ref"`
}

type sandboxExecutor struct {
	mu         sync.Mutex
	executions map[string]SandboxExecution
	httpClient *http.Client
}

func newSandboxExecutor() *sandboxExecutor {
	return &sandboxExecutor{
		executions: make(map[string]SandboxExecution),
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
}

func (e *sandboxExecutor) execute(p TradeProposal) (SandboxExecution, error) {
	apiKey := os.Getenv("BINANCE_TESTNET_API_KEY")
	apiSecret := os.Getenv("BINANCE_TESTNET_API_SECRET")
	if apiKey == "" || apiSecret == "" {
		return SandboxExecution{}, fmt.Errorf("BINANCE_TESTNET_API_KEY / BINANCE_TESTNET_API_SECRET are not configured")
	}
	if p.Action != "buy" && p.Action != "sell" {
		return SandboxExecution{}, fmt.Errorf("cannot execute action %q on sandbox", p.Action)
	}
	side := "BUY"
	if p.Action == "sell" {
		side = "SELL"
	}
	symbol := "BTCUSDT"
	if sm := strings.ToUpper(strings.TrimSpace(os.Getenv("SANDBOX_MARKET"))); sm != "" {
		symbol = sm
	}

	params := url.Values{}
	params.Set("symbol", symbol)
	params.Set("side", side)
	params.Set("type", "MARKET")
	params.Set("quoteOrderQty", fmt.Sprintf("%.2f", p.QuoteAmount))
	params.Set("timestamp", strconv.FormatInt(time.Now().UnixMilli(), 10))

	mac := hmac.New(sha256.New, []byte(apiSecret))
	mac.Write([]byte(params.Encode()))
	signature := hex.EncodeToString(mac.Sum(nil))
	params.Set("signature", signature)

	req, err := http.NewRequest(http.MethodPost, binanceTestnetBase+"/api/v3/order?"+params.Encode(), nil)
	if err != nil {
		return SandboxExecution{}, err
	}
	req.Header.Set("X-MBX-APIKEY", apiKey)

	resp, err := e.httpClient.Do(req)
	if err != nil {
		return SandboxExecution{}, fmt.Errorf("binance testnet request failed: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)

	var result struct {
		OrderID int64  `json:"orderId"`
		Status  string `json:"status"`
		Msg     string `json:"msg"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		return SandboxExecution{}, fmt.Errorf("could not parse binance response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return SandboxExecution{}, fmt.Errorf("binance testnet rejected order: %s", result.Msg)
	}

	now := time.Now().UTC()
	id, err := newProposalID()
	if err != nil {
		return SandboxExecution{}, err
	}
	x := SandboxExecution{
		ID: id, ProposalID: p.ID, Market: symbol, Action: p.Action,
		QuoteAmount: p.QuoteAmount, Status: "filled", ExecutedAt: now,
		BinanceRef: fmt.Sprintf("BINANCE-TESTNET-%d", result.OrderID),
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, exists := e.executions[p.ID]; exists {
		return SandboxExecution{}, fmt.Errorf("proposal already executed")
	}
	e.executions[p.ID] = x
	return x, nil
}

func (e *sandboxExecutor) list() []SandboxExecution {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := make([]SandboxExecution, 0, len(e.executions))
	for _, x := range e.executions {
		out = append(out, x)
	}
	return out
}
