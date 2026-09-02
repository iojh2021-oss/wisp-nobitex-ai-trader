package main

import (
	"testing"
	"time"
)

func TestApprovalGateLifecycle(t *testing.T) {
	gate := newApprovalGate(time.Minute, newSettingsStore())
	proposal, err := gate.create("BTCIRT", tradeDecision{Action: "buy", QuoteAmount: 100, Confidence: 0.9, Reason: "test"})
	if err != nil { t.Fatal(err) }
	if proposal.Status != "pending" { t.Fatalf("status=%q", proposal.Status) }

	approved, err := gate.approve(proposal.ID)
	if err != nil { t.Fatal(err) }
	if approved.Status != "approved" { t.Fatalf("status=%q", approved.Status) }

	if _, err := gate.approve(proposal.ID); err == nil { t.Fatal("expected second approval to fail") }
}

func TestApprovalGateExpires(t *testing.T) {
	gate := newApprovalGate(time.Millisecond, newSettingsStore())
	proposal, err := gate.create("BTCIRT", tradeDecision{Action: "sell", QuoteAmount: 100, Confidence: 0.9, Reason: "test"})
	if err != nil { t.Fatal(err) }
	time.Sleep(5 * time.Millisecond)
	if _, err := gate.approve(proposal.ID); err == nil { t.Fatal("expected expired proposal") }
}
