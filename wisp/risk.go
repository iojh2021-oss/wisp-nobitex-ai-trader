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

func finite(v float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0)
}

func (r *riskGate) validate(side string, quoteAmount float64) error {
	if side != "buy" && side != "sell" && side != "hold" {
		return fmt.Errorf("invalid action %q", side)
	}
	if side == "hold" {
		return nil
	}
	if !finite(quoteAmount) || quoteAmount <= 0 {
		return fmt.Errorf("quote amount must be a finite positive number")
	}
	if r.maxTradeQuote <= 0 || !finite(r.maxTradeQuote) {
		return fmt.Errorf("invalid max trade quote configuration")
	}
	if r.maxDailyLossQuote <= 0 || !finite(r.maxDailyLossQuote) {
		return fmt.Errorf("invalid max daily loss configuration")
	}
	if quoteAmount > r.maxTradeQuote {
		return fmt.Errorf("trade exceeds max quote limit: %.2f > %.2f", quoteAmount, r.maxTradeQuote)
	}
	if !finite(r.dailyLossQuote) || r.dailyLossQuote < 0 {
		return fmt.Errorf("invalid daily loss state")
	}
	if r.dailyLossQuote >= r.maxDailyLossQuote {
		return fmt.Errorf("daily loss limit reached")
	}
	return nil
}
