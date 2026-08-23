import pytest

from trader.config import Settings


def test_testnet_mode_requires_testnet_token():
    settings = Settings(
        paper_trading=False,
        testnet_trading_enabled=True,
        live_trading_enabled=False,
        nobitex_testnet_token=None,
    )
    with pytest.raises(ValueError, match="Testnet trading requires"):
        settings.validate()


def test_testnet_mode_is_mutually_exclusive_with_paper():
    settings = Settings(
        paper_trading=True,
        testnet_trading_enabled=True,
        live_trading_enabled=False,
        nobitex_testnet_token="sandbox-token",
    )
    with pytest.raises(ValueError, match="mutually exclusive"):
        settings.validate()


def test_live_and_testnet_cannot_be_enabled_together():
    settings = Settings(
        paper_trading=False,
        testnet_trading_enabled=True,
        live_trading_enabled=True,
        nobitex_testnet_token="sandbox-token",
        nobitex_token="production-token",
    )
    with pytest.raises(ValueError, match="mutually exclusive"):
        settings.validate()
