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

type nobitexClient struct {
	baseURL string
	token   string
	http    *http.Client
}

func newNobitexClient(baseURL, token string) *nobitexClient {
	return &nobitexClient{baseURL: strings.TrimRight(baseURL, "/"), token: token, http: &http.Client{Timeout: 15 * time.Second}}
}

func (c *nobitexClient) request(ctx context.Context, method, path string, body any, dst any) error {
	var reader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil { return err }
		reader = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, reader)
	if err != nil { return err }
	req.Header.Set("Accept", "application/json")
	if body != nil { req.Header.Set("Content-Type", "application/json") }
	if c.token != "" { req.Header.Set("Authorization", "Token "+c.token) }
	resp, err := c.http.Do(req)
	if err != nil { return err }
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil { return err }
	if resp.StatusCode < 200 || resp.StatusCode >= 300 { return fmt.Errorf("nobitex %s %s: status %d: %s", method, path, resp.StatusCode, strings.TrimSpace(string(data))) }
	if dst == nil { return nil }
	return json.Unmarshal(data, dst)
}

func (c *nobitexClient) marketStats(ctx context.Context, src, dst string) (map[string]any, error) {
	var out map[string]any
	err := c.request(ctx, http.MethodGet, "/market/stats?srcCurrency="+src+"&dstCurrency="+dst, nil, &out)
	return out, err
}

func (c *nobitexClient) orderBook(ctx context.Context, market string) (map[string]any, error) {
	var out map[string]any
	err := c.request(ctx, http.MethodGet, "/v3/orderbook/"+strings.ToUpper(market), nil, &out)
	return out, err
}

func (c *nobitexClient) wallet(ctx context.Context) (map[string]any, error) {
	var out map[string]any
	err := c.request(ctx, http.MethodGet, "/users/wallets/list", nil, &out)
	return out, err
}

func (c *nobitexClient) addOrder(ctx context.Context, payload map[string]any) (map[string]any, error) {
	var out map[string]any
	err := c.request(ctx, http.MethodPost, "/market/orders/add", payload, &out)
	return out, err
}
