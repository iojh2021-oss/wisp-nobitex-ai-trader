# Wisp Nobitex AI Trader

Safety-first trading-agent foundation using **Wisp** as the long-running Go strategy runtime, **OpenAI** as the decision engine, and a **Nobitex REST/WebSocket adapter**.

> **Current mode: paper-safe by default.** No API secret is stored in this repository and the current Wisp runtime does not submit live exchange orders.

## Architecture

```text
Nobitex market data → OpenAI decision → deterministic Risk Gate → Trade Proposal
                                                            ↓
                                                     Approval Dashboard
                                                       ↙           ↘
                                                    Reject       Approve
                                                                    ↓
                                                             Paper Executor
                                                                    ↓
                                                             Execution Result

                         Android APK
                              ↓ HTTPS
                    Wisp REST/Control API
```

## Android app

The repository now contains an Android Compose control panel under `android/`. It is intentionally a **control/monitoring client**, not the 24/7 trading engine. The Wisp backend remains the long-running runtime so Android background restrictions cannot stop strategy execution.

The app currently provides a mobile dashboard foundation for backend health and AI proposals. Live order execution is disabled. API credentials must remain server-side; never put a Nobitex token into the APK.

### Build APK

GitHub Actions workflow: `.github/workflows/android.yml`.

It builds a debug APK and uploads it as the `wisp-trader-debug-apk` workflow artifact. The workflow can also be started manually with **Run workflow**.

For local builds:

```bash
cd android
gradle assembleDebug
```

## Mobile web dashboard

The Wisp process also serves a responsive mobile web dashboard on `APPROVAL_BIND_ADDR` (default `127.0.0.1:8787`). On the same Android device running Termux, open:

```text
http://127.0.0.1:8787/
```

## Read-only Nobitex check

Configure the token only in the backend runtime environment:

```bash
export NOBITEX_API_TOKEN='YOUR_TOKEN'
export NOBITEX_BASE_URL='https://api.nobitex.ir'
```

The read-only route is isolated from the Paper Executor and does not create/cancel orders or withdraw funds.

## Repository layout

- `src/trader/agent.py` — OpenAI decision engine.
- `src/trader/risk.py` — deterministic limits.
- `src/trader/paper.py` — isolated paper broker.
- `src/trader/nobitex.py` — Nobitex adapter; token is runtime-only.
- `src/trader/websocket.py` — reconnecting market stream.
- `src/trader/runtime.py` — risk-gated orchestration.
- `wisp/` — Wisp Go runtime, approval gate, dashboard and paper executor.
- `android/` — Android Compose control/monitoring app.

## Security gates

1. Paper trading is the default.
2. Wisp refuses automatic live execution.
3. Risk limits are enforced outside the LLM.
4. Read-only Nobitex checks are isolated from execution.
5. Secrets belong in deployment environment variables, never Git or the APK.

## Important

This software does not guarantee profitability. An LLM can make incorrect decisions. Real-money deployment requires additional security, operational monitoring, and explicit review.
