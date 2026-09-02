package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestApprovalToPaperExecutionE2E(t *testing.T) {
	g := newApprovalGate(0, newSettingsStore())
	p, err := g.create("BTCUSDT", tradeDecision{
		Action:      "buy",
		QuoteAmount: 100,
		Confidence:  0.91,
		Reason:      "e2e test proposal",
	})
	if err != nil {
		t.Fatalf("create proposal: %v", err)
	}

	srv := g.serve()
	ts := httptest.NewServer(srv.Handler)
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/proposals")
	if err != nil {
		t.Fatalf("GET /proposals: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /proposals status = %d", resp.StatusCode)
	}
	var proposals []TradeProposal
	if err := json.NewDecoder(resp.Body).Decode(&proposals); err != nil {
		t.Fatalf("decode proposals: %v", err)
	}
	if len(proposals) != 1 || proposals[0].ID != p.ID || proposals[0].Status != "pending" {
		t.Fatalf("unexpected proposal state: %+v", proposals)
	}

	resp2, err := http.Get(ts.URL + "/approve?id=" + p.ID)
	if err != nil {
		t.Fatalf("GET /approve: %v", err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("GET /approve status = %d", resp2.StatusCode)
	}
	var approved TradeProposal
	if err := json.NewDecoder(resp2.Body).Decode(&approved); err != nil {
		t.Fatalf("decode approval: %v", err)
	}
	if approved.Status != "approved" {
		t.Fatalf("proposal status = %q, want approved", approved.Status)
	}

	resp3, err := http.Get(ts.URL + "/executions")
	if err != nil {
		t.Fatalf("GET /executions: %v", err)
	}
	defer resp3.Body.Close()
	if resp3.StatusCode != http.StatusOK {
		t.Fatalf("GET /executions status = %d", resp3.StatusCode)
	}
	var executions []PaperExecution
	if err := json.NewDecoder(resp3.Body).Decode(&executions); err != nil {
		t.Fatalf("decode executions: %v", err)
	}
	if len(executions) != 1 {
		t.Fatalf("executions = %d, want 1", len(executions))
	}
	if executions[0].ProposalID != p.ID || executions[0].Status != "filled" || executions[0].SimulatedRef == "" {
		t.Fatalf("unexpected execution: %+v", executions[0])
	}
}

func TestDashboardServesHTML(t *testing.T) {
	g := newApprovalGate(0, newSettingsStore())
	ts := httptest.NewServer(g.serve().Handler)
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/")
	if err != nil {
		t.Fatalf("GET /: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET / status = %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct == "" || ct[:len("text/html")] != "text/html" {
		t.Fatalf("Content-Type = %q, want text/html", ct)
	}
}
