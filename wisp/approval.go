package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// TradeProposal is an advisory decision waiting for explicit human approval
// (paper) or automatic execution (live, when enabled from the dashboard).
type TradeProposal struct {
	ID          string    `json:"id"`
	CreatedAt   time.Time `json:"created_at"`
	ExpiresAt   time.Time `json:"expires_at"`
	Market      string    `json:"market"`
	Action      string    `json:"action"`
	QuoteAmount float64   `json:"quote_amount"`
	Confidence  float64   `json:"confidence"`
	Reason      string    `json:"reason"`
	Status      string    `json:"status"`
}

type approvalGate struct {
	mu         sync.Mutex
	pending    map[string]TradeProposal
	ttl        time.Duration
	executor   *paperExecutor
	executions map[string]PaperExecution
	live       *liveExecutor
	settings   *settingsStore
}

func newApprovalGate(ttl time.Duration, settings *settingsStore) *approvalGate {
	if ttl <= 0 {
		ttl = 2 * time.Minute
	}
	risk := &riskGate{maxTradeQuote: envFloat("MAX_TRADE_QUOTE_LIVE", 0), maxDailyLossQuote: envFloat("MAX_DAILY_LOSS_LIVE", 0)}
	return &approvalGate{
		pending:    make(map[string]TradeProposal),
		ttl:        ttl,
		executor:   newPaperExecutor(),
		executions: make(map[string]PaperExecution),
		live:       newLiveExecutor(risk, settings),
		settings:   settings,
	}
}

func newProposalID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func (g *approvalGate) create(market string, d tradeDecision) (TradeProposal, error) {
	id, err := newProposalID()
	if err != nil {
		return TradeProposal{}, err
	}
	now := time.Now().UTC()
	p := TradeProposal{ID: id, CreatedAt: now, ExpiresAt: now.Add(g.ttl), Market: market, Action: d.Action, QuoteAmount: d.QuoteAmount, Confidence: d.Confidence, Reason: d.Reason, Status: "pending"}
	g.mu.Lock()
	defer g.mu.Unlock()
	g.pending[id] = p
	return p, nil
}

func (g *approvalGate) approve(id string) (TradeProposal, error) {
	g.mu.Lock()
	p, ok := g.pending[id]
	if !ok {
		g.mu.Unlock()
		return TradeProposal{}, fmt.Errorf("proposal not found")
	}
	if !time.Now().UTC().Before(p.ExpiresAt) {
		p.Status = "expired"
		g.pending[id] = p
		g.mu.Unlock()
		return p, fmt.Errorf("proposal expired")
	}
	if p.Status != "pending" {
		g.mu.Unlock()
		return p, fmt.Errorf("proposal status is %s", p.Status)
	}
	if g.executor == nil {
		g.mu.Unlock()
		return p, fmt.Errorf("paper executor is not configured")
	}
	g.mu.Unlock()

	x, err := g.executor.execute(p)
	g.mu.Lock()
	defer g.mu.Unlock()
	if err != nil {
		p.Status = "execution_failed"
		g.pending[id] = p
		return p, fmt.Errorf("paper execution: %w", err)
	}
	p.Status = "approved"
	g.pending[id] = p
	g.executions[id] = x
	return p, nil
}

func (g *approvalGate) approveLive(id string, confirmPhrase string) (TradeProposal, error) {
	g.mu.Lock()
	p, ok := g.pending[id]
	if !ok {
		g.mu.Unlock()
		return TradeProposal{}, fmt.Errorf("proposal not found")
	}
	if !time.Now().UTC().Before(p.ExpiresAt) {
		p.Status = "expired"
		g.pending[id] = p
		g.mu.Unlock()
		return p, fmt.Errorf("proposal expired")
	}
	if p.Status != "pending" {
		g.mu.Unlock()
		return p, fmt.Errorf("proposal status is %s", p.Status)
	}
	if g.live == nil {
		g.mu.Unlock()
		return p, fmt.Errorf("live executor is not configured")
	}
	g.mu.Unlock()

	x, err := g.live.execute(p, confirmPhrase)
	g.mu.Lock()
	defer g.mu.Unlock()
	if err != nil {
		p.Status = "execution_failed"
		g.pending[id] = p
		return p, fmt.Errorf("live execution: %w", err)
	}
	p.Status = "approved_live"
	g.pending[id] = p
	_ = x
	return p, nil
}

func (g *approvalGate) deny(id string) (TradeProposal, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	p, ok := g.pending[id]
	if !ok {
		return TradeProposal{}, fmt.Errorf("proposal not found")
	}
	if p.Status != "pending" {
		return p, fmt.Errorf("proposal status is %s", p.Status)
	}
	p.Status = "denied"
	g.pending[id] = p
	return p, nil
}

func (g *approvalGate) list() []TradeProposal {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := time.Now().UTC()
	out := make([]TradeProposal, 0, len(g.pending))
	for id, p := range g.pending {
		if p.Status == "pending" && !now.Before(p.ExpiresAt) {
			p.Status = "expired"
			g.pending[id] = p
		}
		out = append(out, p)
	}
	return out
}

func (g *approvalGate) listExecutions() []PaperExecution {
	g.mu.Lock()
	defer g.mu.Unlock()
	out := make([]PaperExecution, 0, len(g.executions))
	for _, x := range g.executions {
		out = append(out, x)
	}
	return out
}

func (g *approvalGate) serve() *http.Server {
	addr := strings.TrimSpace(os.Getenv("APPROVAL_BIND_ADDR"))
	if addr == "" {
		if port, err := strconv.Atoi(strings.TrimSpace(os.Getenv("PORT"))); err == nil && port > 0 && port <= 65535 {
			addr = fmt.Sprintf("0.0.0.0:%d", port)
		} else {
			addr = "127.0.0.1:8787"
		}
	}
	token := strings.TrimSpace(os.Getenv("APPROVAL_TOKEN"))
	mux := http.NewServeMux()
	auth := func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if token != "" && r.Header.Get("Authorization") != "Bearer "+token {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			next(w, r)
		}
	}
	mux.HandleFunc("/", auth(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(uiHTML))
	}))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	mux.HandleFunc("/proposals", auth(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, g.list()) }))
	mux.HandleFunc("/executions", auth(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, g.listExecutions()) }))
	mux.HandleFunc("/approve", auth(func(w http.ResponseWriter, r *http.Request) {
		p, err := g.approve(r.URL.Query().Get("id"))
		if err != nil {
			writeJSONStatus(w, http.StatusConflict, map[string]any{"error": err.Error(), "proposal": p})
			return
		}
		writeJSON(w, p)
	}))
	mux.HandleFunc("/approve-live", auth(func(w http.ResponseWriter, r *http.Request) {
		p, err := g.approveLive(r.URL.Query().Get("id"), r.URL.Query().Get("confirm"))
		if err != nil {
			writeJSONStatus(w, http.StatusConflict, map[string]any{"error": err.Error(), "proposal": p})
			return
		}
		writeJSON(w, p)
	}))
	mux.HandleFunc("/deny", auth(func(w http.ResponseWriter, r *http.Request) {
		p, err := g.deny(r.URL.Query().Get("id"))
		if err != nil {
			writeJSONStatus(w, http.StatusConflict, map[string]any{"error": err.Error(), "proposal": p})
			return
		}
		writeJSON(w, p)
	}))
	mux.HandleFunc("/nobitex/readonly", auth(nobitexReadOnlyHandler))
	mux.HandleFunc("/settings", auth(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			settingsPostHandler(g.settings)(w, r)
			return
		}
		settingsGetHandler(g.settings)(w, r)
	}))
	return &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
}

func nobitexReadOnlyHandler(w http.ResponseWriter, r *http.Request) {
	baseURL := strings.TrimRight(os.Getenv("NOBITEX_BASE_URL"), "/")
	if baseURL == "" {
		baseURL = "https://apiv2.nobitex.ir"
	}
	token := strings.TrimSpace(os.Getenv("NOBITEX_API_TOKEN"))
	if token == "" {
		token = strings.TrimSpace(os.Getenv("NOBITEX_TOKEN"))
	}
	if token == "" {
		writeJSONStatus(w, http.StatusServiceUnavailable, map[string]any{"error": "NOBITEX_API_TOKEN is not configured"})
		return
	}
	market := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("market")))
	if market == "" {
		market = "BTCIRT"
	}
	src, dst := splitMarket(market)
	client := newNobitexClient(baseURL, token)
	ctx := r.Context()
	stats, err := client.marketStats(ctx, src, dst)
	if err != nil {
		writeJSONStatus(w, http.StatusBadGateway, map[string]any{"error": "market stats: " + err.Error()})
		return
	}
	book, err := client.orderBook(ctx, market)
	if err != nil {
		writeJSONStatus(w, http.StatusBadGateway, map[string]any{"error": "orderbook: " + err.Error()})
		return
	}
	wallets, err := client.wallet(ctx)
	if err != nil {
		writeJSONStatus(w, http.StatusBadGateway, map[string]any{"error": "wallets: " + err.Error()})
		return
	}
	writeJSON(w, map[string]any{"mode": "read_only", "market": market, "stats": stats, "orderbook": book, "wallets": wallets})
}

func writeJSON(w http.ResponseWriter, value any) { writeJSONStatus(w, http.StatusOK, value) }
func writeJSONStatus(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
