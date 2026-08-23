import pytest

from trader.config import Settings
from trader.testnet import NobitexTestnetClient, NobitexTestnetExecutor


def test_testnet_requires_explicit_token_and_flag():
    settings = Settings(testnet_trading_enabled=True, paper_trading=False, nobitex_testnet_token=None)
    with pytest.raises(ValueError, match="NOBITEX_TESTNET_TOKEN"):
        settings.validate()


def test_testnet_and_paper_cannot_run_together():
    settings = Settings(testnet_trading_enabled=True, paper_trading=True, nobitex_testnet_token="sandbox-token")
    with pytest.raises(ValueError, match="cannot be combined"):
        settings.validate()


@pytest.mark.asyncio
async def test_quote_order_is_translated_to_spot_amount(monkeypatch):
    client = NobitexTestnetClient("sandbox-token")
    executor = NobitexTestnetExecutor(client)
    captured = {}

    async def fake_add_order(payload):
        captured.update(payload)
        return {"status": "ok"}

    monkeypatch.setattr(client, "add_order", fake_add_order)
    result = await executor.place_limit_from_quote(
        market="BTCIRT",
        side="buy",
        quote_amount=1_000_000,
        price=100_000_000,
        client_order_id="wisp-ai-test",
    )

    assert result["status"] == "ok"
    assert captured["type"] == "buy"
    assert captured["srcCurrency"] == "btc"
    assert captured["dstCurrency"] == "rls"
    assert float(captured["amount"]) == pytest.approx(0.01)
    assert captured["execution"] == "limit"
    assert captured["clientOrderId"] == "wisp-ai-test"
    await client.close()
