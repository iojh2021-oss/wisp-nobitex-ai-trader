# Wisp Nobitex AI Trader

Safety-first trading-agent foundation using **Wisp**, **OpenAI**, and a **Nobitex REST/WebSocket adapter**.

> **Current mode: paper-safe by default.** Live financial order execution is disabled.

## Android app — standalone, no Termux

The APK under `android/` is now **standalone**. It does not require Termux, a local Go process, `127.0.0.1`, or a cloud Wisp backend just to perform market-data and AI paper-analysis flows.

```text
Android APK
   │
   ├── Nobitex HTTPS (read-only market data)
   │
   ├── OpenAI Responses API (ChatGPT model)
   │
   ├── deterministic Risk Gate
   │
   ├── AI Trade Proposal
   │
   └── explicit approval → local Paper Executor
```

The app can:

- read Nobitex market data directly over HTTPS;
- ask an OpenAI GPT model to analyze the supplied market snapshot;
- create a structured BUY / SELL / HOLD proposal;
- reject weak proposals automatically through a deterministic confidence gate;
- require explicit user approval before a paper execution;
- keep the OpenAI API key and optional Nobitex API token encrypted with Android Keystore;
- never call a Nobitex order endpoint in the Android paper-trading flow.

### Credentials

The standalone APK intentionally asks the user for their own credentials rather than embedding shared secrets in the application:

- **OpenAI API key** — used for AI analysis. An OpenAI API key is separate from a ChatGPT web subscription.
- **Nobitex API token** — optional for public market-data requests; needed for private account data in future read-only features.

Keys are stored locally using Android Keystore. They are not committed to GitHub and are not sent to the Wisp backend.

## Cloud Wisp backend

The Go/Wisp backend remains available as a separate server-side deployment for long-running strategy orchestration, monitoring, and the existing approval dashboard. `render.yaml` defines a Render deployment.

The cloud service is **not required by the standalone APK**.

## Build APK

GitHub Actions workflow: `.github/workflows/android.yml`.

It builds a debug APK and uploads it as the `wisp-trader-debug-apk` workflow artifact. It also supports **Run workflow** manually.

For local builds:

```bash
cd android
gradle assembleDebug
```

## Nobitex integration

The Android market-data client uses the current Nobitex API v2 base URL (`https://apiv2.nobitex.ir`) and only reads market information in the standalone paper-trading flow.

The repository also contains the server-side Nobitex adapter and an isolated read-only endpoint. Live order execution remains disabled.

## Repository layout

- `android/` — standalone Android Compose app.
- `android/.../LocalTradingEngine.kt` — direct Nobitex market-data, OpenAI analysis, risk gate, and paper execution logic.
- `android/.../SecureTokenStore.kt` — Android Keystore-backed secret storage.
- `src/trader/` — Python strategy/benchmark components.
- `wisp/` — Go/Wisp runtime, approval gate, dashboard, and paper executor.
- `Dockerfile` / `render.yaml` — optional cloud backend deployment.
- `.github/workflows/android.yml` — APK build.
- `.github/workflows/ci.yml` — repository CI.

## Security gates

1. Paper trading is the default.
2. The standalone APK does not expose a live-order function.
3. AI confidence is checked by deterministic application code; the model cannot override the risk limit.
4. A BUY/SELL proposal requires explicit user approval before paper execution.
5. Secrets are stored with Android Keystore and never committed to Git.
6. Network calls use HTTPS.

## Important

This software does not guarantee profitability. An LLM can make incorrect decisions. Real-money trading requires additional validation, monitoring, exchange-specific safeguards, and a carefully reviewed live-execution design.
