from __future__ import annotations

import json
from typing import Any

from openai import AsyncOpenAI


SYSTEM_PROMPT = """You are a crypto market analysis agent.
You may propose only buy, sell, or hold. Never invent account balances or prices.
The decision is advisory: a deterministic risk engine and execution layer must validate every order.
"""

DECISION_SCHEMA = {
    "type": "json_schema",
    "name": "trade_decision",
    "strict": True,
    "schema": {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "action": {"type": "string", "enum": ["buy", "sell", "hold"]},
            "quote_amount": {"type": "number", "minimum": 0},
            "confidence": {"type": "number", "minimum": 0, "maximum": 1},
            "reason": {"type": "string"},
        },
        "required": ["action", "quote_amount", "confidence", "reason"],
    },
}


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
            text={"format": DECISION_SCHEMA},
        )
        decision = json.loads(response.output_text)
        if decision["action"] not in {"buy", "sell", "hold"}:
            raise ValueError("Agent returned an invalid action")
        if decision["quote_amount"] < 0 or not 0 <= decision["confidence"] <= 1:
            raise ValueError("Agent returned invalid numeric fields")
        return decision
