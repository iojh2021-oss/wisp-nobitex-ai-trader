package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type tradeDecision struct {
	Action      string  `json:"action"`
	QuoteAmount float64 `json:"quote_amount"`
	Confidence  float64 `json:"confidence"`
	Reason      string  `json:"reason"`
}

type openAIClient struct {
	apiKey string
	model  string
	http   *http.Client
}

func newOpenAIClient(apiKey, model string) *openAIClient {
	return &openAIClient{apiKey: apiKey, model: model, http: &http.Client{Timeout: 45 * time.Second}}
}

func (c *openAIClient) decide(ctx context.Context, market, portfolio map[string]any) (tradeDecision, error) {
	var zero tradeDecision
	if c.apiKey == "" { return zero, fmt.Errorf("OPENAI_API_KEY is required") }
	input, err := json.Marshal(map[string]any{"market": market, "portfolio": portfolio})
	if err != nil { return zero, err }
	payload := map[string]any{
		"model": c.model,
		"instructions": "You are a crypto market analysis agent. Propose only buy, sell, or hold. Never invent prices or balances. Your decision is advisory; deterministic risk controls and execution gates are authoritative.",
		"input": string(input),
		"text": map[string]any{"format": map[string]any{
			"type": "json_schema",
			"name": "trade_decision",
			"strict": true,
			"schema": map[string]any{
				"type": "object", "additionalProperties": false,
				"properties": map[string]any{
					"action": map[string]any{"type": "string", "enum": []string{"buy", "sell", "hold"}},
					"quote_amount": map[string]any{"type": "number", "minimum": 0},
					"confidence": map[string]any{"type": "number", "minimum": 0, "maximum": 1},
					"reason": map[string]any{"type": "string"},
				},
				"required": []string{"action", "quote_amount", "confidence", "reason"},
			},
		}},
	}
	data, err := json.Marshal(payload)
	if err != nil { return zero, err }
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.openai.com/v1/responses", bytes.NewReader(data))
	if err != nil { return zero, err }
	req.Header.Set("Authorization", "Bearer "+c.apiKey)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil { return zero, err }
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil { return zero, err }
	if resp.StatusCode < 200 || resp.StatusCode >= 300 { return zero, fmt.Errorf("OpenAI responses API status %d: %s", resp.StatusCode, strings.TrimSpace(string(body))) }

	var raw struct {
		Output []struct {
			Content []struct { Text string `json:"text"` } `json:"content"`
		} `json:"output"`
	}
	if err := json.Unmarshal(body, &raw); err != nil { return zero, err }
	for _, item := range raw.Output {
		for _, content := range item.Content {
			if strings.TrimSpace(content.Text) == "" { continue }
			var decision tradeDecision
			if err := json.Unmarshal([]byte(content.Text), &decision); err != nil { return zero, fmt.Errorf("invalid OpenAI decision: %w", err) }
			if decision.Action != "buy" && decision.Action != "sell" && decision.Action != "hold" { return zero, fmt.Errorf("invalid action %q", decision.Action) }
			if decision.QuoteAmount < 0 || decision.Confidence < 0 || decision.Confidence > 1 { return zero, fmt.Errorf("invalid decision values") }
			return decision, nil
		}
	}
	return zero, fmt.Errorf("OpenAI response contained no decision text")
}
