from trader.risk import RiskEngine


def test_trade_limit():
    risk = RiskEngine(100, 500)
    risk.validate_order(100)
    try:
        risk.validate_order(101)
    except ValueError:
        return
    raise AssertionError("order over limit was accepted")


def test_daily_loss_halts():
    risk = RiskEngine(100, 50)
    risk.record_pnl(-50)
    try:
        risk.validate_order(1)
    except RuntimeError:
        return
    raise AssertionError("trading was not halted")
