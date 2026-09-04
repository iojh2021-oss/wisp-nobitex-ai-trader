package main

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

type sessionStore struct {
	mu       sync.Mutex
	sessions map[string]time.Time
	ttl      time.Duration
}

func newSessionStore() *sessionStore {
	return &sessionStore{sessions: make(map[string]time.Time), ttl: 24 * time.Hour}
}

func isAuthenticated(r *http.Request, sessions *sessionStore, legacyToken string) bool {
	if legacyToken != "" {
		auth := strings.TrimSpace(r.Header.Get("Authorization"))
		if strings.HasPrefix(auth, "Bearer ") && strings.TrimSpace(strings.TrimPrefix(auth, "Bearer ")) == legacyToken {
			return true
		}
	}
	if sessions == nil {
		return false
	}
	c, err := r.Cookie("wisp_session")
	if err != nil || c.Value == "" {
		return false
	}
	sessions.mu.Lock()
	defer sessions.mu.Unlock()
	expires, ok := sessions.sessions[c.Value]
	if !ok {
		return false
	}
	if time.Now().After(expires) {
		delete(sessions.sessions, c.Value)
		return false
	}
	return true
}

func loginHandler(sessions *sessionStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		token := strings.TrimSpace(r.FormValue("token"))
		expected := strings.TrimSpace(os.Getenv("APPROVAL_TOKEN"))
		if expected == "" || token == "" || token != expected {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		b := make([]byte, 32)
		if _, err := rand.Read(b); err != nil {
			http.Error(w, "session error", http.StatusInternalServerError)
			return
		}
		sessionID := hex.EncodeToString(b)
		sessions.mu.Lock()
		sessions.sessions[sessionID] = time.Now().Add(sessions.ttl)
		sessions.mu.Unlock()
		secure := strings.EqualFold(strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")), "https")
		http.SetCookie(w, &http.Cookie{
			Name: "wisp_session", Value: sessionID, Path: "/", HttpOnly: true,
			Secure: secure, SameSite: http.SameSiteStrictMode, MaxAge: int(sessions.ttl.Seconds()),
		})
		http.Redirect(w, r, "/", http.StatusSeeOther)
	}
}

func logoutHandler(sessions *sessionStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie("wisp_session"); err == nil {
			sessions.mu.Lock()
			delete(sessions.sessions, c.Value)
			sessions.mu.Unlock()
		}
		http.SetCookie(w, &http.Cookie{Name: "wisp_session", Value: "", Path: "/", HttpOnly: true, MaxAge: -1, SameSite: http.SameSiteStrictMode})
		http.Redirect(w, r, "/", http.StatusSeeOther)
	}
}

const loginHTML = `<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Wisp Login</title>
<style>body{font-family:system-ui;background:#080d1a;color:#eef;display:grid;place-items:center;min-height:100vh;margin:0}.card{width:min(420px,90vw);background:#121a2b;border:1px solid #273451;border-radius:16px;padding:24px}input,button{width:100%;padding:12px;border-radius:10px;margin-top:10px}input{background:#0d1424;color:#fff;border:1px solid #34415f}button{background:#2dd4bf;border:0;font-weight:700;cursor:pointer}</style></head>
<body><form class="card" method="post" action="/login"><h1>Wisp AI Trader</h1><p>Enter the approval token to continue.</p><input name="token" type="password" autocomplete="current-password" required><button>Login</button></form></body></html>`
