package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// TradeProposal is an advisory decision waiting for explicit human approval.
// Approval never bypasses deterministic risk validation.
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
}

func newApprovalGate(ttl time.Duration) *approvalGate {
	if ttl <= 0 {
		ttl = 2 * time.Minute
	}
	return &approvalGate{pending: make(map[string]TradeProposal), ttl: ttl, executions: make(map[string]PaperExecution)}
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

	// Execute outside the gate mutex so a slow executor cannot block proposal reads.
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
	addr := os.Getenv("APPROVAL_BIND_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8787"
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
		id := r.URL.Query().Get("id")
		p, err := g.approve(id)
		if err != nil {
			writeJSONStatus(w, http.StatusConflict, map[string]any{"error": err.Error(), "proposal": p})
			return
		}
		writeJSON(w, p)
	}))
	mux.HandleFunc("/deny", auth(func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		p, err := g.deny(id)
		if err != nil {
			writeJSONStatus(w, http.StatusConflict, map[string]any{"error": err.Error(), "proposal": p})
			return
		}
		writeJSON(w, p)
	}))
	return &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
}

const uiHTML = `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Wisp AI Trader</title><style>body{font-family:system-ui;background:#0b1020;color:#eef;margin:0;padding:20px}main{max-width:900px;margin:auto}.card{background:#151d33;border:1px solid #293452;border-radius:16px;padding:18px;margin:12px 0}button{border:0;border-radius:10px;padding:10px 14px;margin:5px;cursor:pointer}.ok{background:#2dd4bf}.no{background:#fb7185}.muted{color:#9aa7c1}.row{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}.pill{padding:5px 9px;border-radius:999px;background:#263252}</style></head><body><main><h1>Wisp AI Trader</h1><p class="muted">Paper trading dashboard — approvals never send real exchange orders.</p><section><h2>Pending / recent proposals</h2><div id="proposals">Loading…</div></section><section><h2>Paper executions</h2><div id="executions">Loading…</div></section></main><script>async function api(p){const r=await fetch(p);return r.json()}function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}async function approve(id){await fetch('/approve?id='+encodeURIComponent(id));load()}async function deny(id){await fetch('/deny?id='+encodeURIComponent(id));load()}async function load(){const ps=await api('/proposals');document.querySelector('#proposals').innerHTML=ps.length?ps.reverse().map(p=>`<div class="card"><div class="row"><b>${esc(p.action).toUpperCase()} ${esc(p.market)}</b><span class="pill">${esc(p.status)}</span></div><p>Amount: ${Number(p.quote_amount).toFixed(2)} · Confidence: ${(Number(p.confidence)*100).toFixed(1)}%</p><p class="muted">${esc(p.reason)}</p>${p.status==='pending'?`<button class="ok" onclick="approve('${esc(p.id)}')">Approve & Execute Paper</button><button class="no" onclick="deny('${esc(p.id)}')">Reject</button>`:''}</div>`).join(''):'<p class="muted">No proposals yet.</p>';const xs=await api('/executions');document.querySelector('#executions').innerHTML=xs.length?xs.reverse().map(x=>`<div class="card"><b>${esc(x.action).toUpperCase()} ${esc(x.market)}</b><p>${Number(x.quote_amount).toFixed(2)} · ${esc(x.status)}</p><small class="muted">${esc(x.simulated_ref)}</small></div>`).join(''):'<p class="muted">No paper executions yet.</p>'}load();setInterval(load,5000)</script></body></html>`

func writeJSON(w http.ResponseWriter, value any) { writeJSONStatus(w, http.StatusOK, value) }
func writeJSONStatus(w http.ResponseWriter, status int, value any) { w.Header().Set("Content-Type", "application/json"); w.WriteHeader(status); _ = json.NewEncoder(w).Encode(value) }
