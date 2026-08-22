from __future__ import annotations

from dataclasses import dataclass
from math import sqrt
from statistics import mean, stdev
from typing import Iterable


@dataclass(frozen=True)
class Bar:
    close: float


@dataclass(frozen=True)
class Trade:
    index: int
    side: str
    price: float
    quote_amount: float


@dataclass(frozen=True)
class BacktestResult:
    equity: float
    return_pct: float
    max_drawdown_pct: float
    trades: int
    win_rate_pct: float
    sharpe: float


def sma(values: list[float], window: int) -> float:
    if len(values) < window:
        return mean(values)
    return mean(values[-window:])


def momentum_signal(closes: list[float], fast: int = 10, slow: int = 30) -> str:
    if len(closes) < 3:
        return "hold"
    fast_ma = sma(closes, min(fast, len(closes)))
    slow_ma = sma(closes, min(slow, len(closes)))
    if fast_ma > slow_ma * 1.001:
        return "buy"
    if fast_ma < slow_ma * 0.999:
        return "sell"
    return "hold"


def ensemble_signal(closes: list[float]) -> tuple[str, float]:
    """Conservative deterministic signal used as a baseline and AI safety prior."""
    if len(closes) < 30:
        return "hold", 0.0
    votes = [momentum_signal(closes, 5, 20), momentum_signal(closes, 10, 30)]
    buy = votes.count("buy")
    sell = votes.count("sell")
    if buy == 2:
        return "buy", 0.75
    if sell == 2:
        return "sell", 0.75
    return "hold", 0.5


def backtest(bars: Iterable[Bar], initial_quote: float = 10_000.0, fee_rate: float = 0.001) -> BacktestResult:
    bars = list(bars)
    cash = initial_quote
    asset = 0.0
    equity_curve: list[float] = []
    closed_pnls: list[float] = []
    entry = None
    trades = 0

    for i, bar in enumerate(bars):
        closes = [b.close for b in bars[: i + 1]]
        signal, _ = ensemble_signal(closes)
        if signal == "buy" and cash > 0:
            spend = cash * 0.95
            asset = (spend * (1 - fee_rate)) / bar.close
            cash -= spend
            entry = bar.close
            trades += 1
        elif signal == "sell" and asset > 0:
            proceeds = asset * bar.close * (1 - fee_rate)
            cash += proceeds
            if entry is not None:
                closed_pnls.append(proceeds - (asset * entry))
            asset = 0.0
            entry = None
            trades += 1
        equity_curve.append(cash + asset * bar.close)

    if asset and bars:
        final = asset * bars[-1].close * (1 - fee_rate)
        cash += final
        if entry is not None:
            closed_pnls.append(final - asset * entry)
        equity_curve[-1] = cash

    peak = initial_quote
    max_dd = 0.0
    for value in equity_curve:
        peak = max(peak, value)
        max_dd = max(max_dd, (peak - value) / peak if peak else 0.0)
    returns = []
    for a, b in zip(equity_curve, equity_curve[1:]):
        if a:
            returns.append(b / a - 1)
    sharpe = 0.0
    if len(returns) > 1 and stdev(returns) > 0:
        sharpe = sqrt(365) * mean(returns) / stdev(returns)
    win_rate = 100 * sum(p > 0 for p in closed_pnls) / len(closed_pnls) if closed_pnls else 0.0
    return BacktestResult(
        equity=cash,
        return_pct=(cash / initial_quote - 1) * 100,
        max_drawdown_pct=max_dd * 100,
        trades=trades,
        win_rate_pct=win_rate,
        sharpe=sharpe,
    )


def should_enable_ai(ai_return_pct: float, baseline_return_pct: float, ai_max_drawdown_pct: float,
                     baseline_max_drawdown_pct: float, minimum_observations: int) -> bool:
    """Only allow AI promotion after an out-of-sample comparison and risk check."""
    if minimum_observations < 100:
        return False
    return ai_return_pct > baseline_return_pct and ai_max_drawdown_pct <= baseline_max_drawdown_pct * 1.10
