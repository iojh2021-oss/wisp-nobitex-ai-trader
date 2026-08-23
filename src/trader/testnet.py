from __future__ import annotations

from typing import Any

from .nobitex import NobitexClient


TESTNET_BASE_URL = "https://testnetapi.nobitex.ir"
TESTNET_WEB_URL = "https://testnet.nobitex.ir"


class NobitexTestnetClient(NobitexClient):
    """Nobitex sandbox adapter.

    This class is deliberately separate from the production client so a testnet
    token/base URL cannot accidentally be mixed with production settings.
    """

    def __init__(self, token: str, timeout: float = 15.0) -> None:
        if not token:
            raise ValueError("Nobitex testnet token is required")
        super().__init__(TESTNET_BASE_URL, token=token, timeout=timeout)


class NobitexTestnetExecutor:
    """Small, explicit testnet order executor used only by the AI runtime."""

    def __init__(self, client: NobitexTestnetClient) -> None:
        self.client = client

    async def place_limit_from_quote(
        self,
        market: str,
        side: str,
        quote_amount: float,
        price: float,
        client_order_id: str,
    ) -> dict[str, Any]:
        if side not in {"buy", "sell"}:
            raise ValueError("side must be buy or sell")
        if quote_amount <= 0 or price <= 0:
            raise ValueError("quote_amount and price must be positive")

        src, dst = market.upper().split("IRT", 1) if market.upper().endswith("IRT") else (None, None)
        if src is None or not src:
            raise ValueError("testnet executor currently expects an IRT market such as BTCIRT")
        dst = "rls"
        amount = quote_amount / price
        payload = {
            "type": side,
            "srcCurrency": src.lower(),
            "dstCurrency": dst,
            "amount": f"{amount:.12f}",
            "price": price,
            "execution": "limit",
            "clientOrderId": client_order_id[:32],
        }
        return await self.client.add_order(payload)
