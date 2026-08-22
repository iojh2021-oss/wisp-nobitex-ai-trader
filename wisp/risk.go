package main

import "fmt"

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
	if quoteAmount <= 0 {
		return fmt.Errorf("quote amount must be positive")
	}
	if quoteAmount > r.maxTradeQuote {
		return fmt.Errorf("trade exceeds max quote limit: %.2f > %.2f", quoteAmount, r.maxTradeQuote)
	}
	if r.dailyLossQuote >= r.maxDailyLossQuote {
		return fmt.Errorf("daily loss limit reached")
	}
	return nil
}
