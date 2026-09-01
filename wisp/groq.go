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

type groqClient struct {
	apiKey string
	model  string
	http   *http.Client
}

func newGroqClient(apiKey, model string) *groqClient {
	return &groqClient{apiKey: apiKey, model: model, http: &http.Client{Timeout: 45 * time.Second}}
}

func (c *groqClient) decide(ctx context.Context, market, portfolio map[string]any) (tradeDecision, error) {
	var zero tradeDecision
	if c.apiKey == "" {
		return zero, fmt.Errorf("GROQ_API_KEY is required")
	}
	input, err := json.Marshal(map[string]any{"market": market, "portfolio": portfolio})
	if err != nil {
		return zero, err
	}
	systemPrompt := "You are a crypto market analysis agent. Propose only buy, sell, or hold. " +
		"Never invent prices or balances. Your decision is advisory; deterministic risk controls " +
		"and execution gates are authoritative. Respond with ONLY a JSON object, no other text, " +
		"matching exactly: {\"action\":\"buy|sell|hold\",\"quote_amount\":<number,>=0>,\"confidence\":<0..1>,\"reason\":\"<string>\"}"
	payload := map[string]any{
		"model": c.model,
		"messages": []map[string]string{
			{"role": "system", "content": systemPrompt},
			{"role": "user", "content": string(input)},
		},
		"response_format": map[string]string{"type": "json_object"},
		"temperature":      0.2,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return zero, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.groq.com/openai/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		return zero, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.apiKey)

	resp, err := c.http.Do(req)
	if err != nil {
		return zero, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return zero, err
	}
	if resp.StatusCode != http.StatusOK {
		return zero, fmt.Errorf("Groq chat completions status %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}

	var parsed struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return zero, fmt.Errorf("decode groq response: %w", err)
	}
	if len(parsed.Choices) == 0 {
		return zero, fmt.Errorf("groq: empty choices")
	}
	var decision tradeDecision
	if err := json.Unmarshal([]byte(parsed.Choices[0].Message.Content), &decision); err != nil {
		return zero, fmt.Errorf("decode trade decision: %w", err)
	}
	return decision, nil
}
