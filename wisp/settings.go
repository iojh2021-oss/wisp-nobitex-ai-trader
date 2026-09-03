package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"

	_ "github.com/lib/pq"
)

// Settings holds the AI provider selection and trading mode, editable at
// runtime from the dashboard UI and persisted in Postgres so it survives
// restarts. Mode is one of:
//   "paper"   - local simulation only, no order ever leaves this server
//   "sandbox" - real order placed on the Binance Spot Test Network (fake funds)
//   "live"    - real order placed on Nobitex with real money
type Settings struct {
	AIProvider   string `json:"ai_provider"`
	OpenAIAPIKey string `json:"openai_api_key,omitempty"`
	OpenAIModel  string `json:"openai_model"`
	GroqAPIKey   string `json:"groq_api_key,omitempty"`
	GroqModel    string `json:"groq_model"`
	Mode         string `json:"mode"`
}

type publicSettings struct {
	AIProvider   string `json:"ai_provider"`
	HasOpenAIKey bool   `json:"has_openai_key"`
	OpenAIModel  string `json:"openai_model"`
	HasGroqKey   bool   `json:"has_groq_key"`
	GroqModel    string `json:"groq_model"`
	Mode         string `json:"mode"`
	Persisted    bool   `json:"persisted"`
}

type settingsStore struct {
	mu   sync.Mutex
	db   *sql.DB
	memo Settings
}

func defaultSettings() Settings {
	return Settings{
		AIProvider:  "openai",
		OpenAIModel: "gpt-5-mini",
		GroqModel:   "openai/gpt-oss-120b",
		Mode:        "paper",
	}
}

func normalizeMode(m string) string {
	switch m {
	case "sandbox", "live":
		return m
	default:
		return "paper"
	}
}

func newSettingsStore() *settingsStore {
	dsn := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	s := &settingsStore{memo: defaultSettings()}
	if dsn == "" {
		return s
	}
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		fmt.Fprintf(os.Stderr, "settings: db open failed, falling back to in-memory: %v\n", err)
		return s
	}
	if err := db.Ping(); err != nil {
		fmt.Fprintf(os.Stderr, "settings: db ping failed, falling back to in-memory: %v\n", err)
		return s
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS bot_settings (
		id INT PRIMARY KEY DEFAULT 1,
		ai_provider TEXT NOT NULL DEFAULT 'openai',
		openai_api_key TEXT NOT NULL DEFAULT '',
		openai_model TEXT NOT NULL DEFAULT 'gpt-5-mini',
		groq_api_key TEXT NOT NULL DEFAULT '',
		groq_model TEXT NOT NULL DEFAULT 'openai/gpt-oss-120b',
		mode TEXT NOT NULL DEFAULT 'paper',
		CONSTRAINT single_row CHECK (id = 1)
	)`); err != nil {
		fmt.Fprintf(os.Stderr, "settings: schema init failed, falling back to in-memory: %v\n", err)
		return s
	}
	// Migrate older installs that still have the two-boolean schema.
	_, _ = db.Exec(`ALTER TABLE bot_settings ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'paper'`)
	if _, err := db.Exec(`INSERT INTO bot_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING`); err != nil {
		fmt.Fprintf(os.Stderr, "settings: seed row failed: %v\n", err)
	}
	s.db = db
	return s
}

func (s *settingsStore) persisted() bool { return s.db != nil }

func (s *settingsStore) get() Settings {
	if s.db == nil {
		s.mu.Lock()
		defer s.mu.Unlock()
		return s.memo
	}
	var st Settings
	row := s.db.QueryRow(`SELECT ai_provider, openai_api_key, openai_model, groq_api_key, groq_model, mode FROM bot_settings WHERE id=1`)
	if err := row.Scan(&st.AIProvider, &st.OpenAIAPIKey, &st.OpenAIModel, &st.GroqAPIKey, &st.GroqModel, &st.Mode); err != nil {
		return defaultSettings()
	}
	st.Mode = normalizeMode(st.Mode)
	return st
}

func (s *settingsStore) save(in Settings) error {
	in.Mode = normalizeMode(in.Mode)
	if in.AIProvider != "groq" {
		in.AIProvider = "openai"
	}
	if s.db == nil {
		s.mu.Lock()
		defer s.mu.Unlock()
		if in.OpenAIAPIKey == "" {
			in.OpenAIAPIKey = s.memo.OpenAIAPIKey
		}
		if in.GroqAPIKey == "" {
			in.GroqAPIKey = s.memo.GroqAPIKey
		}
		if in.OpenAIModel == "" {
			in.OpenAIModel = s.memo.OpenAIModel
		}
		if in.GroqModel == "" {
			in.GroqModel = s.memo.GroqModel
		}
		s.memo = in
		return nil
	}
	current := s.get()
	if in.OpenAIAPIKey == "" {
		in.OpenAIAPIKey = current.OpenAIAPIKey
	}
	if in.GroqAPIKey == "" {
		in.GroqAPIKey = current.GroqAPIKey
	}
	if in.OpenAIModel == "" {
		in.OpenAIModel = current.OpenAIModel
	}
	if in.GroqModel == "" {
		in.GroqModel = current.GroqModel
	}
	_, err := s.db.Exec(`UPDATE bot_settings SET ai_provider=$1, openai_api_key=$2, openai_model=$3, groq_api_key=$4, groq_model=$5, mode=$6 WHERE id=1`,
		in.AIProvider, in.OpenAIAPIKey, in.OpenAIModel, in.GroqAPIKey, in.GroqModel, in.Mode)
	return err
}

func settingsGetHandler(store *settingsStore) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		st := store.get()
		writeJSON(w, publicSettings{
			AIProvider:   st.AIProvider,
			HasOpenAIKey: st.OpenAIAPIKey != "" || os.Getenv("OPENAI_API_KEY") != "",
			OpenAIModel:  st.OpenAIModel,
			HasGroqKey:   st.GroqAPIKey != "" || os.Getenv("GROQ_API_KEY") != "",
			GroqModel:    st.GroqModel,
			Mode:         st.Mode,
			Persisted:    store.persisted(),
		})
	}
}

func settingsPostHandler(store *settingsStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var in Settings
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			writeJSONStatus(w, http.StatusBadRequest, map[string]any{"error": "invalid JSON: " + err.Error()})
			return
		}
		if err := store.save(in); err != nil {
			writeJSONStatus(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		settingsGetHandler(store)(w, r)
	}
}
