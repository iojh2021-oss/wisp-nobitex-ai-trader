from __future__ import annotations

from typing import Any

import httpx


class NobitexClient:
    """Read-oriented Nobitex REST client. Order submission is intentionally not exposed yet."""

    def __init__(self, base_url: str, token: str | None = None, timeout: float = 15.0) -> None:
        self.base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(timeout=timeout)
        self._headers = {"Authorization": f"Token {token}"} if token else {}

    async def close(self) -> None:
        await self._client.aclose()

    async def ticker(self, symbol: str) -> dict[str, Any]:
        response = await self._client.get(
            f"{self.base_url}/market/stats",
            params={"srcCurrency": symbol.lower(), "dstCurrency": "usdt"},
            headers=self._headers,
        )
        response.raise_for_status()
        return response.json()

    async def orderbook(self, symbol: str) -> dict[str, Any]:
        response = await self._client.get(
            f"{self.base_url}/v3/orderbook/{symbol.lower()}usdt",
            headers=self._headers,
        )
        response.raise_for_status()
        return response.json()
