from __future__ import annotations

import json
from typing import Any

from openai import AsyncOpenAI


SYSTEM_PROMPT = """You are a crypto market analysis agent. Return a JSON decision only.
Allowed actions: buy, sell, hold. Never invent account balances or prices.
This agent is advisory: a deterministic risk engine and execution layer must validate every order.
"""


class OpenAITradingAgent:
    def __init__(self, api_key: str, model: str = "gpt-5-mini") -> None:
        self.client = AsyncOpenAI(api_key=api_key)
        self.model = model

    async def decide(self, market: dict[str, Any], portfolio: dict[str, Any]) -> dict[str, Any]:
        payload = {"market": market, "portfolio": portfolio}
        response = await self.client.responses.create(
            model=self.model,
            instructions=SYSTEM_PROMPT,
            input=json.dumps(payload, ensure_ascii=False),
        )
        text = response.output_text.strip()
        decision = json.loads(text)
        if decision.get("action") not in {"buy", "sell", "hold"}:
            raise ValueError("Agent returned an invalid action")
        return decision
