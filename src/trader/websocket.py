from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

import websockets


class NobitexMarketStream:
    """Reconnectable public market stream. Authentication/private channels are added later."""

    def __init__(self, url: str) -> None:
        self.url = url
        self._stop = False

    def stop(self) -> None:
        self._stop = True

    async def events(self, subscribe_message: dict[str, Any]) -> AsyncIterator[dict[str, Any]]:
        delay = 1.0
        while not self._stop:
            try:
                async with websockets.connect(self.url, ping_interval=20, ping_timeout=20) as ws:
                    await ws.send(json.dumps(subscribe_message))
                    delay = 1.0
                    async for raw in ws:
                        yield json.loads(raw)
            except (OSError, asyncio.TimeoutError, websockets.WebSocketException):
                if self._stop:
                    return
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30.0)
