package main

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const nobitexOrderURL = "https://apiv2.nobitex.ir/market/orders/add"
const nobitexOrderPath = "/market/orders/add"

// LiveExecution places a REAL order using real money. Whether this path is
// enabled is controlled by the dashboard's Live Trading toggle (persisted
// in the settings store) rather than an environment variable.
type LiveExecution struct {
	ID          string    `json:"id"`
	ProposalID  string    `json:"proposal_id"`
	Market      string    `json:"market"`
	Action      string    `json:"action"`
	QuoteAmount float64   `json:"quote_amount"`
	Status      string    `json:"status"`
	ExecutedAt  time.Time `json:"executed_at"`
	NobitexRef  string    `json:"nobitex_ref"`
	AuthMode    string    `json:"auth_mode"`
}

type liveExecutor struct {
	mu         sync.Mutex
	executions map[string]LiveExecution
	risk       *riskGate
	httpClient *http.Client
	settings   *settingsStore
}

func newLiveExecutor(risk *riskGate, settings *settingsStore) *liveExecutor {
	return &liveExecutor{
		executions: make(map[string]LiveExecution),
		risk:       risk,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		settings:   settings,
	}
}

func mustDecodeEd25519Seed(raw string) ed25519.PrivateKey {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	decoded, err := decodeBase64URLAny(raw)
	if err != nil {
		return nil
	}
	switch len(decoded) {
	case ed25519.SeedSize:
		return ed25519.NewKeyFromSeed(decoded)
	case ed25519.PrivateKeySize:
		return ed25519.PrivateKey(decoded)
	default:
		return nil
	}
}

func decodeBase64URLAny(s string) ([]byte, error) {
	if b, err := base64.URLEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	if b, err := base64.RawURLEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	return nil, fmt.Errorf("invalid base64")
}

func signNobitexRequest(privateKey ed25519.PrivateKey, apiKey, method, path string, body []byte) (key, signature, timestamp string) {
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	payload := ts + method + path + string(body)
	sig := ed25519.Sign(privateKey, []byte(payload))
	return apiKey, base64.URLEncoding.EncodeToString(sig), ts
}

func (e *liveExecutor) execute(p TradeProposal, confirmPhrase string) (LiveExecution, error) {
	enabled := os.Getenv("LIVE_TRADING_ENABLED") == "true"
	if e.settings != nil {
		enabled = e.settings.get().LiveTradingEnabled
	}
	if !enabled {
		return LiveExecution{}, fmt.Errorf("live trading is disabled (enable it from the dashboard)")
	}

	apiKey := os.Getenv("NOBITEX_API_KEY")
	privateKey := mustDecodeEd25519Seed(os.Getenv("NOBITEX_API_PRIVATE_KEY"))
	apiToken := os.Getenv("NOBITEX_API_TOKEN")
	signedAuth := apiKey != "" && privateKey != nil
	if !signedAuth && apiToken == "" {
		return LiveExecution{}, fmt.Errorf("no Nobitex credentials configured: set NOBITEX_API_KEY + NOBITEX_API_PRIVATE_KEY (recommended) or NOBITEX_API_TOKEN (legacy)")
	}
	_ = confirmPhrase
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
	req.Header.Set("Content-Type", "application/json")

	authMode := "legacy_token"
	if signedAuth {
		authMode = "signed_api_key"
		key, signature, ts := signNobitexRequest(privateKey, apiKey, http.MethodPost, nobitexOrderPath, reqBody)
		req.Header.Set("Nobitex-Key", key)
		req.Header.Set("Nobitex-Signature", signature)
		req.Header.Set("Nobitex-Timestamp", ts)
	} else {
		req.Header.Set("Authorization", "Token "+apiToken)
	}

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
		AuthMode:   authMode,
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
