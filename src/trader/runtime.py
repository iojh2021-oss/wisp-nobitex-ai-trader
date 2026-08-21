from __future__ import annotations

from .paper import PaperBroker
from .risk import RiskEngine


class TradingRuntime:
    """Safe orchestration skeleton: Agent proposes; risk validates; paper broker executes."""

    def __init__(self, risk: RiskEngine, broker: PaperBroker) -> None:
        self.risk = risk
        self.broker = broker

    def execute_decision(self, symbol: str, decision: dict) -> object | None:
        action = decision.get("action", "hold")
        if action == "hold":
            return None
        quote_amount = float(decision.get("quote_amount", 0))
        self.risk.validate_order(quote_amount)
        return self.broker.place(symbol, action, quote_amount)
