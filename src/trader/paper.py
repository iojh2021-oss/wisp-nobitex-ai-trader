from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class PaperOrder:
    symbol: str
    side: str
    quote_amount: float
    status: str = "filled"


@dataclass
class PaperBroker:
    cash_quote: float = 10_000_000.0
    orders: list[PaperOrder] = field(default_factory=list)

    def place(self, symbol: str, side: str, quote_amount: float) -> PaperOrder:
        if side not in {"buy", "sell"}:
            raise ValueError("side must be buy or sell")
        if quote_amount <= 0:
            raise ValueError("quote_amount must be positive")
        if side == "buy" and quote_amount > self.cash_quote:
            raise ValueError("insufficient paper balance")
        if side == "buy":
            self.cash_quote -= quote_amount
        else:
            self.cash_quote += quote_amount
        order = PaperOrder(symbol, side, quote_amount)
        self.orders.append(order)
        return order
