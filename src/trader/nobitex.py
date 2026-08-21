from __future__ import annotations

from typing import Any

import httpx


class NobitexClient:
    """Nobitex REST adapter. Credentials are supplied only at runtime via environment variables."""

    def __init__(self, base_url: str, token: str | None = None, timeout: float = 15.0) -> None:
        self.base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(timeout=timeout)
        self._headers = {"Authorization": f"Token {token}"} if token else {}

    async def close(self) -> None:
        await self._client.aclose()

    async def ticker(self, src_currency: str, dst_currency: str) -> dict[str, Any]:
        response = await self._client.get(
            f"{self.base_url}/market/stats",
            params={"srcCurrency": src_currency.lower(), "dstCurrency": dst_currency.lower()},
            headers=self._headers,
        )
        response.raise_for_status()
        return response.json()

    async def orderbook(self, market: str) -> dict[str, Any]:
        response = await self._client.get(
            f"{self.base_url}/v3/orderbook/{market.upper()}", headers=self._headers
        )
        response.raise_for_status()
        return response.json()

    async def ohlc(self, market: str, resolution: str = "5", countback: int = 200) -> dict[str, Any]:
        response = await self._client.get(
            f"{self.base_url}/market/udf/history",
            params={"symbol": market.upper(), "resolution": resolution, "countback": countback},
            headers=self._headers,
        )
        response.raise_for_status()
        return response.json()

    async def wallet(self) -> dict[str, Any]:
        response = await self._client.get(f"{self.base_url}/users/wallets/list", headers=self._headers)
        response.raise_for_status()
        return response.json()

    async def open_orders(self, src_currency: str, dst_currency: str) -> dict[str, Any]:
        response = await self._client.get(
            f"{self.base_url}/market/orders/list",
            params={"srcCurrency": src_currency.lower(), "dstCurrency": dst_currency.lower(), "status": "open"},
            headers=self._headers,
        )
        response.raise_for_status()
        return response.json()

    async def add_order(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Live-capable endpoint; callers must enforce the separate live-trading gate first."""
        response = await self._client.post(
            f"{self.base_url}/market/orders/add", json=payload, headers=self._headers
        )
        response.raise_for_status()
        return response.json()

    async def order_status(self, order_id: int | None = None, client_order_id: str | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if order_id is not None:
            payload["id"] = order_id
        elif client_order_id:
            payload["clientOrderId"] = client_order_id
        else:
            raise ValueError("order_id or client_order_id is required")
        response = await self._client.post(
            f"{self.base_url}/market/orders/status", json=payload, headers=self._headers
        )
        response.raise_for_status()
        return response.json()

    async def update_order_status(self, order_id: int, status: str = "canceled") -> dict[str, Any]:
        if status != "canceled":
            raise ValueError("Only cancellation is exposed by this safety-first adapter")
        response = await self._client.post(
            f"{self.base_url}/market/orders/update-status",
            json={"order": order_id, "status": status},
            headers=self._headers,
        )
        response.raise_for_status()
        return response.json()
