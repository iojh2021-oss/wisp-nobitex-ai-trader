package main

import (
	"fmt"
	"math"
)

type riskGate struct {
	maxTradeQuote     float64
	maxDailyLossQuote float64
	dailyLossQuote    float64
}

func (r *riskGate) validate(side string, quoteAmount float64) error {
	if side != "buy" && side != "sell" && side != "hold" {
		return fmt.Errorf("invalid action %q", side)
	}
	if side == "hold" {
		return nil
	}
	if !math.IsFinite(quoteAmount) || quoteAmount <= 0 {
		return fmt.Errorf("quote amount must be a finite positive number")
	}
	if r.maxTradeQuote <= 0 || !math.IsFinite(r.maxTradeQuote) {
		return fmt.Errorf("invalid max trade quote configuration")
	}
	if r.maxDailyLossQuote <= 0 || !math.IsFinite(r.maxDailyLossQuote) {
		return fmt.Errorf("invalid max daily loss configuration")
	}
	if quoteAmount > r.maxTradeQuote {
		return fmt.Errorf("trade exceeds max quote limit: %.2f > %.2f", quoteAmount, r.maxTradeQuote)
	}
	if !math.IsFinite(r.dailyLossQuote) || r.dailyLossQuote < 0 {
		return fmt.Errorf("invalid daily loss state")
	}
	if r.dailyLossQuote >= r.maxDailyLossQuote {
		return fmt.Errorf("daily loss limit reached")
	}
	return nil
}
