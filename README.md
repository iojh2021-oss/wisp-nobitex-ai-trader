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
                    Cloud Wisp REST/Control API
```

## Android app — no Termux required

The Android Compose app under `android/` is now **cloud-first**. The installed APK does not require Termux, a local Go process, or `127.0.0.1`.

The APK connects over HTTPS to the cloud Wisp control API. It can:

- check backend health;
- load AI trade proposals;
- show market, amount, confidence, reason, and status;
- approve or deny pending proposals;
- keep the backend access token encrypted with Android Keystore;
- remain paper-only while live financial execution is disabled.

OpenAI API credentials and Nobitex credentials stay on the backend. OpenAI explicitly recommends never deploying an API key in a mobile app and routing requests through your own backend. citeturn0search0

### Deploy the cloud backend once

A Render Blueprint is included in `render.yaml` and the service is named `wisp-nobitex-ai-trader-api`.

The deployment needs these secrets in the hosting dashboard:

- `OPENAI_API_KEY` — required for AI decisions.
- `APPROVAL_TOKEN` — private token used by the Android app to access proposals/approval.
- `NOBITEX_API_TOKEN` — optional; only needed for the isolated read-only balance endpoint.

Non-secret defaults such as `OPENAI_MODEL`, `NOBITEX_BASE_URL`, `PAPER_TRADING`, and `LIVE_TRADING_ENABLED` are already defined in `render.yaml`.

The backend listens on the hosting platform's `PORT` and exposes `/healthz`, `/proposals`, `/approve`, `/deny`, `/executions`, and `/nobitex/readonly`.

### Build APK

GitHub Actions workflow: `.github/workflows/android.yml`.

It builds a debug APK and uploads it as the `wisp-trader-debug-apk` workflow artifact. The workflow can also be started manually with **Run workflow**.

For local builds:

```bash
cd android
gradle assembleDebug
```

## Cloud deployment files

- `Dockerfile` — reproducible Linux container for the Wisp backend.
- `render.yaml` — Render web-service configuration.
- `.github/workflows/ci.yml` — also validates the container build.

## Read-only Nobitex check

Configure the token only in the backend runtime environment. The read-only route is isolated from the Paper Executor and does not create/cancel orders or withdraw funds.

## Repository layout

- `src/trader/agent.py` — OpenAI decision engine.
- `src/trader/risk.py` — deterministic limits.
- `src/trader/paper.py` — isolated paper broker.
- `src/trader/nobitex.py` — Nobitex adapter; token is runtime-only.
- `src/trader/websocket.py` — reconnecting market stream.
- `src/trader/runtime.py` — risk-gated orchestration.
- `wisp/` — Wisp Go runtime, approval gate, dashboard and paper executor.
- `android/` — Android Compose control/monitoring app.
- `Dockerfile` — cloud backend container.
- `render.yaml` — cloud deployment definition.

## Security gates

1. Paper trading is the default.
2. Wisp refuses automatic live execution.
3. Risk limits are enforced outside the LLM.
4. Read-only Nobitex checks are isolated from execution.
5. Secrets belong in deployment environment variables, never Git or the APK.
6. Android-to-backend traffic is HTTPS-only in the cloud-first app.

## Important

This software does not guarantee profitability. An LLM can make incorrect decisions. Real-money deployment requires additional security, operational monitoring, and explicit review.
