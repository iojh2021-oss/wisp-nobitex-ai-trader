# Wisp Nobitex AI Trader

Safety-first trading-agent foundation using **Wisp** as the long-running Go strategy runtime, **OpenAI** as the decision engine, and a **Nobitex REST/WebSocket adapter**.

> **Current mode: paper-safe by default.** No API secret is stored in this repository and live execution is disabled unless explicitly enabled at deployment time.

## Architecture

```text
                    ┌─────────────────────┐
                    │      OpenAI LLM     │
                    │  decision / analysis│
                    └──────────┬──────────┘
                               │ proposal
                               ▼
Nobitex REST/WebSocket → Market State → Risk Engine
                               │             │
                               │             ▼
                               └──────→ Wisp Strategy
                                              │
                                   Paper / future live executor
```

## Repository layout

- `src/trader/agent.py` — OpenAI decision engine.
- `src/trader/risk.py` — deterministic limits that the model cannot bypass.
- `src/trader/paper.py` — isolated paper broker.
- `src/trader/nobitex.py` — Nobitex REST adapter. Token is runtime-only.
- `src/trader/websocket.py` — reconnecting Nobitex Centrifugo market stream.
- `src/trader/runtime.py` — risk-gated execution orchestration.
- `wisp/` — Wisp standalone Go strategy/runtime boundary.
- `.env.example` — configuration template with empty secrets.
- `.github/workflows/ci.yml` — Python tests plus Wisp build.

## Nobitex API slot

The only account secret needed later is supplied through `NOBITEX_TOKEN` (and, for private WebSocket channels, `NOBITEX_WEBSOCKET_AUTH_PARAM`). **Do not commit either value.** The adapter already contains the documented market-data, wallet, open-order, order-status, order-create and order-cancel/update operations. The default base URL can be switched to the Nobitex test environment with `NOBITEX_BASE_URL=https://testnetapi.nobitex.ir`.

## OpenAI

Set `OPENAI_API_KEY` locally and choose `OPENAI_MODEL` through the environment. The model only proposes an action; the deterministic risk layer validates the proposal before paper execution.

## Wisp

Wisp currently uses standalone Go strategy processes and a connector interface. This repository includes the Wisp lifecycle/runtime boundary, but Nobitex is not pretended to be an official Wisp connector. The exchange-specific adapter is isolated so it can be registered against Wisp's connector interface when that integration is enabled.

Wisp's current upstream project documents Go 1.26+, standalone strategy processes, and separate exchange connectors. See the upstream project before upgrading the pinned SDK.

## Run tests

```bash
python -m pip install -e '.[test]'
pytest -q

cd wisp
go mod download
gofmt -w .
go build ./...
```

## Security gates

1. Paper trading is the default.
2. `LIVE_TRADING_ENABLED` defaults to `false`.
3. Live mode refuses to start without `NOBITEX_TOKEN`.
4. Live mode cannot be enabled while paper mode is enabled.
5. Per-trade and daily-loss limits are enforced outside the LLM.
6. No withdrawal API is used by the adapter.
7. Secrets belong in deployment secrets/environment variables, never Git.

## Important

This software does not guarantee profitability. An LLM can make incorrect decisions. The risk engine, paper phase, testnet validation, monitoring, and emergency-stop procedures must be completed before considering any real-money deployment.
