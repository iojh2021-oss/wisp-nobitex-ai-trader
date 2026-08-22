package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func TestNobitexReadOnlyRequiresToken(t *testing.T) {
	old := os.Getenv("NOBITEX_API_TOKEN")
	oldLegacy := os.Getenv("NOBITEX_TOKEN")
	_ = os.Unsetenv("NOBITEX_API_TOKEN")
	_ = os.Unsetenv("NOBITEX_TOKEN")
	defer os.Setenv("NOBITEX_API_TOKEN", old)
	defer os.Setenv("NOBITEX_TOKEN", oldLegacy)

	r := httptest.NewRequest(http.MethodGet, "/nobitex/readonly?market=BTCIRT", nil)
	w := httptest.NewRecorder()
	nobitexReadOnlyHandler(w, r)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected %d, got %d", http.StatusServiceUnavailable, w.Code)
	}
}
