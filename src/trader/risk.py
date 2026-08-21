from __future__ import annotations

from dataclasses import dataclass


@dataclass
class RiskState:
    daily_loss_quote: float = 0.0
    halted: bool = False


class RiskEngine:
    """Deterministic guardrail. The LLM never bypasses this layer."""

    def __init__(self, max_trade_quote: float, max_daily_loss_quote: float) -> None:
        self.max_trade_quote = max_trade_quote
        self.max_daily_loss_quote = max_daily_loss_quote
        self.state = RiskState()

    def validate_order(self, quote_amount: float) -> None:
        if self.state.halted:
            raise RuntimeError("Trading is halted")
        if quote_amount <= 0:
            raise ValueError("Order amount must be positive")
        if quote_amount > self.max_trade_quote:
            raise ValueError("Order exceeds max trade limit")
        if self.state.daily_loss_quote >= self.max_daily_loss_quote:
            self.state.halted = True
            raise RuntimeError("Daily loss limit reached")

    def record_pnl(self, pnl_quote: float) -> None:
        if pnl_quote < 0:
            self.state.daily_loss_quote += -pnl_quote
            if self.state.daily_loss_quote >= self.max_daily_loss_quote:
                self.state.halted = True
