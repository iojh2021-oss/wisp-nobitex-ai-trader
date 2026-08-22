from trader.strategy import Bar, backtest, ensemble_signal, should_enable_ai


def test_ensemble_requires_history():
    assert ensemble_signal([100, 101, 102]) == ("hold", 0.0)


def test_backtest_returns_metrics():
    bars = [Bar(float(100 + i)) for i in range(80)]
    result = backtest(bars)
    assert result.equity > 0
    assert result.trades >= 0
    assert 0 <= result.max_drawdown_pct <= 100


def test_ai_promotion_requires_out_of_sample_observations():
    assert not should_enable_ai(20, 10, 5, 5, 99)
    assert should_enable_ai(20, 10, 5, 5, 100)
    assert not should_enable_ai(20, 10, 6, 5, 100)
