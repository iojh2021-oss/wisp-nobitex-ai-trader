from trader.benchmark import Candle, buy_and_hold, moving_average_strategy, run_benchmark


def test_buy_and_hold_accounts_for_entry_and_exit_fee():
    candles = [Candle(i, price) for i, price in enumerate([100, 110, 120])]
    result = run_benchmark(candles, {"hold": buy_and_hold()}, fee=0.001)[0]
    assert result.final_equity == 1197.6012
    assert result.total_return > 0
    assert result.trades == 2


def test_moving_average_strategy_waits_for_enough_history():
    strategy = moving_average_strategy(2, 3)
    assert strategy([100], 0) == "hold"
    assert strategy([100, 101], 1) == "hold"
    assert strategy([100, 101, 102], 2) == "buy"


def test_benchmark_reports_risk_metrics():
    candles = [Candle(i, p) for i, p in enumerate([100, 105, 102, 110, 108, 115])]
    results = run_benchmark(
        candles,
        {"hold": buy_and_hold(), "ma": moving_average_strategy(2, 3)},
        fee=0.001,
    )
    assert {r.name for r in results} == {"hold", "ma"}
    assert all(r.final_equity > 0 for r in results)
    assert all(0 <= r.max_drawdown < 1 for r in results)
    assert all(r.trades >= 0 for r in results)
