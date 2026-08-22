from __future__ import annotations

from dataclasses import dataclass
from math import sqrt
from typing import Callable, Iterable, Sequence


@dataclass(frozen=True)
class Candle:
    timestamp: int
    close: float


@dataclass(frozen=True)
class BenchmarkResult:
    name: str
    initial_cash: float
    final_equity: float
    total_return: float
    max_drawdown: float
    win_rate: float
    sharpe: float
    trades: int


SignalFn = Callable[[Sequence[float], int], str]


def _equity_curve(candles: Sequence[Candle], signals: Sequence[str], initial_cash: float, fee: float) -> tuple[list[float], int, int]:
    cash = initial_cash
    units = 0.0
    entry_equity = 0.0
    wins = 0
    trades = 0
    curve: list[float] = []

    for candle, signal in zip(candles, signals):
        price = candle.close
        if signal == "buy" and cash > 0:
            spend = cash
            units = (spend * (1 - fee)) / price
            cash = 0.0
            entry_equity = units * price
            trades += 1
        elif signal == "sell" and units > 0:
            proceeds = units * price * (1 - fee)
            if proceeds > entry_equity:
                wins += 1
            cash = proceeds
            units = 0.0
            trades += 1
        curve.append(cash + units * price)

    if units > 0:
        final = cash + units * candles[-1].close * (1 - fee)
        if final > entry_equity:
            wins += 1
        trades += 1
    else:
        final = cash

    return curve, trades, wins


def _max_drawdown(curve: Sequence[float]) -> float:
    peak = curve[0] if curve else 0.0
    maximum = 0.0
    for value in curve:
        peak = max(peak, value)
        if peak:
            maximum = max(maximum, (peak - value) / peak)
    return maximum


def _sharpe(curve: Sequence[float]) -> float:
    returns = [(b / a) - 1 for a, b in zip(curve, curve[1:]) if a > 0]
    if len(returns) < 2:
        return 0.0
    mean = sum(returns) / len(returns)
    variance = sum((r - mean) ** 2 for r in returns) / (len(returns) - 1)
    if variance == 0:
        return 0.0
    return sqrt(len(returns)) * mean / sqrt(variance)


def run_benchmark(
    candles: Iterable[Candle],
    strategies: dict[str, SignalFn],
    *,
    initial_cash: float = 1000.0,
    fee: float = 0.001,
    start_index: int = 0,
) -> list[BenchmarkResult]:
    data = list(candles)
    if len(data) < 2:
        raise ValueError("at least two candles are required")
    if initial_cash <= 0:
        raise ValueError("initial_cash must be positive")
    if not 0 <= fee < 1:
        raise ValueError("fee must be in [0, 1)")

    results: list[BenchmarkResult] = []
    prices = [c.close for c in data]
    for name, strategy in strategies.items():
        signals = [strategy(prices[: i + 1], i) for i in range(len(data))]
        curve, trades, wins = _equity_curve(data, signals, initial_cash, fee)
        final_equity = curve[-1]
        results.append(
            BenchmarkResult(
                name=name,
                initial_cash=initial_cash,
                final_equity=final_equity,
                total_return=(final_equity / initial_cash) - 1,
                max_drawdown=_max_drawdown(curve),
                win_rate=(wins / max(1, trades // 2)) if trades else 0.0,
                sharpe=_sharpe(curve),
                trades=trades,
            )
        )
    return results


def moving_average_strategy(short_window: int = 5, long_window: int = 20) -> SignalFn:
    if short_window <= 0 or long_window <= short_window:
        raise ValueError("windows must satisfy 0 < short_window < long_window")

    def signal(prices: Sequence[float], _: int) -> str:
        if len(prices) < long_window:
            return "hold"
        short = sum(prices[-short_window:]) / short_window
        long = sum(prices[-long_window:]) / long_window
        if short > long:
            return "buy"
        if short < long:
            return "sell"
        return "hold"

    return signal


def buy_and_hold() -> SignalFn:
    def signal(_: Sequence[float], index: int) -> str:
        return "buy" if index == 0 else "hold"

    return signal
