from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    nobitex_base_url: str = os.getenv("NOBITEX_BASE_URL", "https://api.nobitex.ir")
    nobitex_testnet_base_url: str = os.getenv("NOBITEX_TESTNET_BASE_URL", "https://testnetapi.nobitex.ir")
    nobitex_ws_url: str = os.getenv("NOBITEX_WS_URL", "wss://wss.nobitex.ir/connection/websocket")
    nobitex_token: str | None = os.getenv("NOBITEX_TOKEN") or None
    nobitex_testnet_token: str | None = os.getenv("NOBITEX_TESTNET_TOKEN") or None
    nobitex_websocket_auth_param: str | None = os.getenv("NOBITEX_WEBSOCKET_AUTH_PARAM") or None
    nobitex_market: str = os.getenv("NOBITEX_MARKET", "BTCIRT").upper()
    openai_api_key: str | None = os.getenv("OPENAI_API_KEY") or None
    openai_model: str = os.getenv("OPENAI_MODEL", "gpt-5-mini")
    paper_trading: bool = _bool("PAPER_TRADING", True)
    testnet_trading_enabled: bool = _bool("TESTNET_TRADING_ENABLED", False)
    live_trading_enabled: bool = _bool("LIVE_TRADING_ENABLED", False)
    max_trade_quote: float = float(os.getenv("MAX_TRADE_QUOTE", "1000000"))
    max_daily_loss_quote: float = float(os.getenv("MAX_DAILY_LOSS_QUOTE", "2000000"))

    def validate(self) -> None:
        active_modes = sum((self.paper_trading, self.testnet_trading_enabled, self.live_trading_enabled))
        if active_modes > 1:
            raise ValueError("PAPER_TRADING, TESTNET_TRADING_ENABLED and LIVE_TRADING_ENABLED are mutually exclusive")
        if self.testnet_trading_enabled and not self.nobitex_testnet_token:
            raise ValueError("Testnet trading requires NOBITEX_TESTNET_TOKEN")
        if self.live_trading_enabled and not self.nobitex_token:
            raise ValueError("Live trading requires NOBITEX_TOKEN")
        if self.max_trade_quote <= 0 or self.max_daily_loss_quote <= 0:
            raise ValueError("Risk limits must be positive")
