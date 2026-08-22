# Wisp Nobitex AI Trader

Safety-first trading-agent foundation using **Wisp** as the long-running Go strategy runtime, **OpenAI** as the decision engine, and a **Nobitex REST/WebSocket adapter**.

> **Current mode: paper-safe by default.** No API secret is stored in this repository and the current Wisp runtime does not submit live exchange orders.

## Architecture

```text
Nobitex public market data ─→ OpenAI decision ─→ deterministic Risk Gate
                                      │
                                      ▼
                               Trade Proposal
                                      │
                              Mobile Web Dashboard
                               ↙             ↘
                           Reject       Approve
                                          │
                                   Paper Executor
                                          │
                                   Execution Result

Optional read-only account check:
Nobitex market data + wallet list ─→ Dashboard
                                    (no order endpoint)
```

## Mobile dashboard

The Wisp process serves a responsive mobile web dashboard on `APPROVAL_BIND_ADDR` (default `127.0.0.1:8787`). On the same Android device running Termux, start the Wisp app and open:

```text
http://127.0.0.1:8787/
```

For access from another device on the same LAN, bind explicitly to the Android device's interface, for example:

```bash
export APPROVAL_BIND_ADDR=0.0.0.0:8787
```

Then open the Android device's LAN IP on the other device. Do not expose the dashboard directly to the public internet; use a private network or authenticated reverse proxy.

If `APPROVAL_TOKEN` is set, API/dashboard requests require `Authorization: Bearer <token>`. For a local-only test you can leave it unset and keep the bind address at `127.0.0.1`.

## Read-only Nobitex check

The dashboard has a **Read-only Nobitex** panel. Configure the token only in the runtime environment:

```bash
export NOBITEX_API_TOKEN='YOUR_TOKEN'
# NOBITEX_TOKEN is also accepted for compatibility.
export NOBITEX_BASE_URL='https://api.nobitex.ir'
```

Then refresh the dashboard and select a market. The server calls only public market-data endpoints plus the authenticated wallet-list endpoint. It does **not** call order creation, cancellation, withdrawal, or any other trading endpoint from this read-only route. Nobitex documents `/v3/orderbook/SYMBOL` as public and `/users/wallets/list` as an authenticated wallet-list endpoint. citeturn1search0

The token is never placed in browser JavaScript, query parameters, source control, or the dashboard response.

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

## Run tests

```bash
python -m pip install -e '.[test]'
pytest -q

cd wisp
go mod download
gofmt -w .
go build ./...
go test ./...
```

## Security gates

1. Paper trading is the default.
2. The Wisp runtime refuses automatic live execution.
3. Per-trade and daily-loss limits are enforced outside the LLM.
4. No withdrawal API is used by the read-only dashboard route.
5. Secrets belong in deployment secrets/environment variables, never Git.
6. Read-only Nobitex checks are isolated from the Paper Executor.

## Important

This software does not guarantee profitability. An LLM can make incorrect decisions. The risk engine, paper phase, monitoring, and emergency-stop procedures must be completed before considering any real-money deployment.
