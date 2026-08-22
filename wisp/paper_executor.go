package main

import (
	"fmt"
	"sync"
	"time"
)

// PaperExecution is a deterministic simulation result. It never calls a
// financial exchange and exists to exercise the complete approval flow.
type PaperExecution struct {
	ID           string    `json:"id"`
	ProposalID   string    `json:"proposal_id"`
	Market       string    `json:"market"`
	Action       string    `json:"action"`
	QuoteAmount  float64   `json:"quote_amount"`
	Status       string    `json:"status"`
	ExecutedAt   time.Time `json:"executed_at"`
	SimulatedRef string    `json:"simulated_ref"`
}

type paperExecutor struct {
	mu         sync.Mutex
	executions map[string]PaperExecution
}

func newPaperExecutor() *paperExecutor {
	return &paperExecutor{executions: make(map[string]PaperExecution)}
}

func (e *paperExecutor) execute(p TradeProposal) (PaperExecution, error) {
	if p.Action != "buy" && p.Action != "sell" {
		return PaperExecution{}, fmt.Errorf("cannot execute action %q", p.Action)
	}
	if p.QuoteAmount <= 0 {
		return PaperExecution{}, fmt.Errorf("quote amount must be positive")
	}
	now := time.Now().UTC()
	id, err := newProposalID()
	if err != nil {
		return PaperExecution{}, err
	}
	x := PaperExecution{
		ID: id, ProposalID: p.ID, Market: p.Market, Action: p.Action,
		QuoteAmount: p.QuoteAmount, Status: "filled", ExecutedAt: now,
		SimulatedRef: "PAPER-" + id,
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, exists := e.executions[p.ID]; exists {
		return PaperExecution{}, fmt.Errorf("proposal already executed")
	}
	e.executions[p.ID] = x
	return x, nil
}

func (e *paperExecutor) list() []PaperExecution {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := make([]PaperExecution, 0, len(e.executions))
	for _, x := range e.executions {
		out = append(out, x)
	}
	return out
}
