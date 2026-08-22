from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path

from trader.agent import OpenAITradingAgent
from trader.benchmark import Candle, buy_and_hold, moving_average_strategy, run_benchmark


def load_candles(path: Path) -> list[Candle]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError("input must be a JSON list")
    return [Candle(timestamp=int(row["timestamp"]), close=float(row["close"])) for row in raw]


async def main() -> None:
    parser = argparse.ArgumentParser(description="Benchmark GPT against deterministic baselines on saved market data")
    parser.add_argument("data", type=Path, help="JSON file containing [{timestamp, close}, ...]")
    parser.add_argument("--model", default=os.getenv("OPENAI_MODEL", "gpt-5-mini"))
    parser.add_argument("--initial-cash", type=float, default=1000.0)
    parser.add_argument("--fee", type=float, default=0.001)
    parser.add_argument("--max-candles", type=int, default=100)
    args = parser.parse_args()

    if not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("OPENAI_API_KEY is required; no key is read from the data file or stored by this script")

    candles = load_candles(args.data)[: args.max_candles]
    agent = OpenAITradingAgent(api_key=os.environ["OPENAI_API_KEY"], model=args.model)
    gpt_actions: list[str] = []
    for index, candle in enumerate(candles):
        decision = await agent.decide(
            market={"timestamp": candle.timestamp, "close": candle.close},
            portfolio={"quote_balance": args.initial_cash, "base_balance": 0.0},
        )
        gpt_actions.append(decision["action"])

    def gpt_strategy(prices: list[float], index: int) -> str:
        return gpt_actions[index]

    results = run_benchmark(
        candles,
        {
            "GPT/OpenAI": gpt_strategy,
            "MA(5/20)": moving_average_strategy(5, 20),
            "Buy & Hold": buy_and_hold(),
        },
        initial_cash=args.initial_cash,
        fee=args.fee,
    )

    for result in sorted(results, key=lambda r: r.total_return, reverse=True):
        print(
            f"{result.name}: return={result.total_return:.2%} "
            f"max_dd={result.max_drawdown:.2%} sharpe={result.sharpe:.3f} "
            f"win_rate={result.win_rate:.2%} trades={result.trades}"
        )


if __name__ == "__main__":
    asyncio.run(main())
