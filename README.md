# Wisp Nobitex AI Trader

A safety-first trading agent architecture using Wisp as the execution/runtime layer, OpenAI as the decision engine, and a Nobitex adapter.

## Safety status

**Paper trading only.** Live order execution is intentionally disabled until the connector, risk controls, tests, and deployment checks are complete.

## Architecture

```text
Nobitex REST/WebSocket
        |
        v
  Nobitex Adapter ----> Market State
        |                    |
        v                    v
   Risk Engine <-------- OpenAI Agent
        |
        v
   Wisp Strategy / Execution
        |
        v
  Paper Portfolio
```

## Goals

- Nobitex REST and WebSocket adapter
- Reconnect and rate-limit handling
- Deterministic risk limits outside the LLM
- OpenAI tool-based decision engine
- Paper trading and audit logs
- Backtesting and integration tests
- 24/7 process supervision after validation
- Explicit emergency stop
- No withdrawal permission required

## Live trading

There is no live trading implementation in the initial scaffold. Any future live order tool must remain behind a hard risk gate and separate credentials.
