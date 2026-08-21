from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

import websockets


class NobitexMarketStream:
    """Reconnectable Centrifugo public market stream used by Nobitex."""

    def __init__(self, url: str, connection_token: str | None = None) -> None:
        self.url = url
        self.connection_token = connection_token
        self._stop = False

    def stop(self) -> None:
        self._stop = True

    async def events(self, market: str) -> AsyncIterator[dict[str, Any]]:
        delay = 1.0
        while not self._stop:
            try:
                async with websockets.connect(self.url, ping_interval=20, ping_timeout=20) as ws:
                    connect: dict[str, Any] = {"connect": {}}
                    if self.connection_token:
                        connect["connect"]["token"] = self.connection_token
                    connect["id"] = 1
                    await ws.send(json.dumps(connect))
                    await ws.send(json.dumps({
                        "id": 2,
                        "subscribe": {"channel": f"public:orderbook-{market.upper()}"},
                    }))
                    delay = 1.0
                    async for raw in ws:
                        message = json.loads(raw)
                        if "push" in message:
                            yield message["push"]
            except (OSError, asyncio.TimeoutError, websockets.WebSocketException):
                if self._stop:
                    return
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30.0)
