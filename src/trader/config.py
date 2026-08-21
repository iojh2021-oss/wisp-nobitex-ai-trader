from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    nobitex_base_url: str = os.getenv("NOBITEX_BASE_URL", "https://api.nobitex.ir")
    nobitex_ws_url: str = os.getenv("NOBITEX_WS_URL", "wss://wss.nobitex.ir/connection/websocket")
    nobitex_token: str | None = os.getenv("NOBITEX_TOKEN")
    openai_api_key: str | None = os.getenv("OPENAI_API_KEY")
    openai_model: str = os.getenv("OPENAI_MODEL", "gpt-5-mini")
    paper_trading: bool = os.getenv("PAPER_TRADING", "true").lower() == "true"
    max_trade_quote: float = float(os.getenv("MAX_TRADE_QUOTE", "1000000"))
    max_daily_loss_quote: float = float(os.getenv("MAX_DAILY_LOSS_QUOTE", "2000000"))

    def validate(self) -> None:
        if not self.paper_trading and not self.nobitex_token:
            raise ValueError("Live trading requires NOBITEX_TOKEN")
        if self.max_trade_quote <= 0 or self.max_daily_loss_quote <= 0:
            raise ValueError("Risk limits must be positive")
