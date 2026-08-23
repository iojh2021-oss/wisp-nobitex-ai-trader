from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from .agent import OpenAITradingAgent
from .config import Settings
from .risk import RiskEngine
from .testnet import NobitexTestnetClient, NobitexTestnetExecutor


class TestnetAIRuntime:
    """One-cycle ChatGPT -> risk -> Nobitex testnet runtime.

    Testnet execution is opt-in. Production order endpoints are never selected
    by this class.
    """

    def __init__(self, settings: Settings) -> None:
        settings.validate()
        if not settings.testnet_trading_enabled:
            raise ValueError("TESTNET_TRADING_ENABLED must be true")
        if not settings.openai_api_key:
            raise ValueError("OPENAI_API_KEY is required for the AI runtime")
        self.settings = settings
        self.client = NobitexTestnetClient(
            settings.nobitex_testnet_token or "",
            base_url=settings.nobitex_testnet_base_url,
        )
        self.executor = NobitexTestnetExecutor(self.client)
        self.agent = OpenAITradingAgent(settings.openai_api_key, settings.openai_model)
        self.risk = RiskEngine(settings.max_trade_quote, settings.max_daily_loss_quote)

    async def close(self) -> None:
        await self.client.close()

    async def market_snapshot(self) -> dict[str, Any]:
        market = self.settings.nobitex_market
        if not market.endswith("IRT"):
            raise ValueError("The initial testnet runtime currently supports IRT spot markets")
        src = market[:-3].lower()
        stats = await self.client.ticker(src, "rls")
        book = await self.client.orderbook(market)
        candles = await self.client.ohlc(market, resolution="5", countback=120)
        wallet = await self.client.wallet()
        return {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "market": market,
            "stats": stats,
            "orderbook": book,
            "ohlc": candles,
            "portfolio": wallet,
        }

    async def analyze_once(self) -> dict[str, Any]:
        snapshot = await self.market_snapshot()
        decision = await self.agent.decide(snapshot, snapshot["portfolio"])
        decision["market"] = snapshot["market"]
        decision["timestamp"] = snapshot["timestamp"]
        decision["risk_approved"] = False
        return decision

    async def trade_once(self) -> dict[str, Any]:
        decision = await self.analyze_once()
        if decision["action"] == "hold":
            return {"decision": decision, "execution": None}
        if decision["confidence"] < self.settings.min_confidence:
            return {
                "decision": decision,
                "execution": None,
                "blocked": "confidence_below_threshold",
            }

        quote_amount = float(decision["quote_amount"])
        self.risk.validate_order(quote_amount)

        market = decision["market"]
        symbol = market[:-3].lower()
        stats = await self.client.ticker(symbol, "rls")
        latest = float(stats["stats"][f"{symbol}-rls"]["latest"])
        client_order_id = f"wisp-ai-{int(datetime.now(timezone.utc).timestamp() * 1000)}"
        execution = await self.executor.place_limit_from_quote(
            market=market,
            side=decision["action"],
            quote_amount=quote_amount,
            price=latest,
            client_order_id=client_order_id,
        )
        decision["risk_approved"] = True
        return {"decision": decision, "execution": execution}
