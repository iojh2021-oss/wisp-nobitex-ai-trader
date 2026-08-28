package ai.wisp.trader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

private const val OPENAI_URL = "https://api.openai.com/v1/responses"
private const val OPENAI_MODEL = "gpt-5.6-luna"
private const val MIN_CONFIDENCE = 0.70
private const val MAX_PAPER_QUOTE = 1_000_000.0

class LocalTradingEngine(private val client: OkHttpClient) {
    data class MarketSnapshot(
        val market: String,
        val lastPrice: String,
        val high: String,
        val low: String,
        val volume: String,
        val bid: String,
        val ask: String,
        val spreadPercent: String,
        val orderBookImbalance: String,
        val tradeBuyRatio: String,
        val rsi14: String,
        val ema20: String,
        val ema50: String,
        val volatilityPercent: String,
        val trend: String,
        val candleCount: Int,
        val recentCandles: String,
        val recentTrades: String,
        val rawStats: String,
        val rawOrderbook: String,
    )

    data class Proposal(
        val id: String,
        val market: String,
        val action: String,
        val quoteAmount: Double,
        val confidence: Double,
        val reason: String,
        var status: String = "pending",
    )

    data class PaperExecution(
        val proposalId: String,
        val market: String,
        val action: String,
        val quoteAmount: Double,
        val reference: String,
    )

    /**
     * Fetches the full read-only public market snapshot used by the AI.
     * The optional token is retained for API compatibility but is deliberately
     * not sent because all endpoints used here are public Nobitex endpoints.
     */
    fun fetchMarket(market: String, nobitexToken: String): MarketSnapshot {
        val repository = NobitexMarketDataRepository(client)
        val data = repository.fetch(market, resolution = "15", candleCount = 100)
        return MarketSnapshot(
            market = data.symbol,
            lastPrice = format(data.lastPrice),
            high = format(data.dayHigh),
            low = format(data.dayLow),
            volume = format(data.volumeSrc),
            bid = format(data.bestBid),
            ask = format(data.bestAsk),
            spreadPercent = format(data.spreadPercent),
            orderBookImbalance = format(data.orderBookImbalance),
            tradeBuyRatio = format(data.tradeBuyRatio),
            rsi14 = format(data.rsi14),
            ema20 = format(data.ema20),
            ema50 = format(data.ema50),
            volatilityPercent = format(data.volatilityPercent),
            trend = data.trend,
            candleCount = data.candles.size,
            recentCandles = data.candles.takeLast(12).joinToString(";") {
                "t=${it.time},o=${it.open},h=${it.high},l=${it.low},c=${it.close},v=${it.volume}"
            },
            recentTrades = data.trades.take(20).joinToString(";") {
                "t=${it.time},p=${it.price},v=${it.volume},type=${it.type}"
            },
            rawStats = "symbol=${data.symbol},dayChange=${format(data.dayChangePercent)}",
            rawOrderbook = "bids=${data.bids.take(20)}, asks=${data.asks.take(20)}",
        )
    }

    fun analyze(snapshot: MarketSnapshot, openAiKey: String): Proposal {
        require(openAiKey.isNotBlank()) { "OpenAI API key is required for ChatGPT analysis" }
        val prompt = """
You are the decision engine for a paper-trading crypto assistant.
Analyze ONLY the supplied Nobitex public market data. Do not invent missing values.
The data layer has already fetched statistics, order book, market depth, recent trades and 15-minute OHLC candles.
Use the derived indicators only as evidence, not as guarantees.

Return JSON only with exactly these fields:
{"action":"buy|sell|hold","quote_amount":number,"confidence":number,"reason":"short explanation"}

Decision rules:
- confidence must be between 0 and 1.
- Prefer HOLD when evidence is weak, contradictory, stale, or incomplete.
- Do not treat an indicator as a guaranteed prediction.
- Consider trend, RSI, EMA20/EMA50 relationship, volatility, spread, order-book imbalance, recent trade flow and candle structure together.
- Avoid BUY/SELL when spread or volatility makes the setup unattractive.
- quote_amount is in the market quote currency and must be <= 1000000.
- This is paper trading only. Never claim that an order was sent to Nobitex.

MARKET SNAPSHOT
symbol=${snapshot.market}
last=${snapshot.lastPrice}
high=${snapshot.high}
low=${snapshot.low}
volume=${snapshot.volume}
best_bid=${snapshot.bid}
best_ask=${snapshot.ask}
spread_percent=${snapshot.spreadPercent}
order_book_imbalance=${snapshot.orderBookImbalance}
recent_trade_buy_ratio=${snapshot.tradeBuyRatio}
rsi14=${snapshot.rsi14}
ema20=${snapshot.ema20}
ema50=${snapshot.ema50}
volatility_percent=${snapshot.volatilityPercent}
trend=${snapshot.trend}
candle_count=${snapshot.candleCount}
recent_15m_candles=${snapshot.recentCandles}
recent_trades=${snapshot.recentTrades}
""".trimIndent()

        val body = JSONObject().apply {
            put("model", OPENAI_MODEL)
            put("input", prompt)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENAI_URL)
            .header("Authorization", "Bearer ${openAiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val responseText = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OpenAI HTTP ${response.code}: ${text.take(300)}")
            text
        }
        val outputText = extractOutputText(JSONObject(responseText))
        val json = JSONObject(cleanJson(outputText))
        val action = json.optString("action", "hold").lowercase(Locale.US)
        val amount = json.optDouble("quote_amount", 0.0).coerceAtLeast(0.0)
        val confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        val reason = json.optString("reason", "No reason provided").trim()

        require(action in setOf("buy", "sell", "hold")) { "AI returned an invalid action" }
        require(amount <= MAX_PAPER_QUOTE) { "AI proposal exceeds the paper-trading limit" }

        return Proposal(
            id = "local-${System.currentTimeMillis()}",
            market = snapshot.market,
            action = action,
            quoteAmount = amount,
            confidence = confidence,
            reason = reason,
            status = if (action == "hold" || confidence < MIN_CONFIDENCE) "blocked_by_risk" else "pending",
        )
    }

    data class Opportunity(
        val market: String,
        val action: String,
        val confidence: Double,
        val reason: String,
        val takeProfit: Double?,
        val stopLoss: Double?,
        val timeframeHint: String,
    )

    /**
     * Scans the top N Nobitex markets by traded volume, enriches them with
     * CoinStats global market data + news (if a key is supplied), and asks
     * the AI to rank the best opportunities with suggested take-profit /
     * stop-loss levels. This does NOT place any order by itself — the
     * result still has to go through analyze()/approvePaper()/approveLive
     * for the chosen market like any other proposal.
     */
    fun scanOpportunities(openAiKey: String, coinStatsApiKey: String, topN: Int = 20): List<Opportunity> {
        require(openAiKey.isNotBlank()) { "OpenAI API key is required" }

        val nobitexRepo = NobitexMarketDataRepository(client)
        val candidates = nobitexRepo.fetchAllStats()
            .filter { it.volumeDst != null && it.lastPrice != null }
            .sortedByDescending { it.volumeDst }
            .take(topN)

        var coins = emptyList<CoinStatsRepository.CoinMarket>()
        var news = emptyList<CoinStatsRepository.NewsItem>()
        if (coinStatsApiKey.isNotBlank()) {
            val coinStats = CoinStatsRepository(client)
            coins = runCatching { coinStats.fetchTopCoins(coinStatsApiKey, limit = 100) }.getOrDefault(emptyList())
            news = runCatching { coinStats.fetchNews(coinStatsApiKey, limit = 20) }.getOrDefault(emptyList())
        }

        val rows = candidates.joinToString("\n") { s ->
            val coin = coins.firstOrNull { it.symbol.equals(s.baseSymbol, ignoreCase = true) }
            val headlines = news.filter { it.title.contains(s.baseSymbol, ignoreCase = true) }.take(2)
            buildString {
                append("market=${s.market} last=${s.lastPrice} dayChangePct=${s.dayChangePercent} volumeQuote=${s.volumeDst}")
                if (coin != null) append(" globalRank=${coin.rank} cap=${coin.marketCap} chg1h=${coin.priceChange1h} chg1d=${coin.priceChange1d} chg1w=${coin.priceChange1w}")
                if (headlines.isNotEmpty()) append(" news=" + headlines.joinToString(" | ") { it.title })
            }
        }

        val prompt = """
You are a cryptocurrency opportunity screener for a paper-trading assistant.
Below is a table of Nobitex market candidates with technical, global market, and news context.
For EACH candidate decide action (buy/sell/hold) and confidence (0-1), then RANK all candidates
and return only the top 5 by opportunity quality, best first.

Rules:
- Prefer candidates where global market context (rank/cap/change) and Nobitex technical data AGREE.
  If evidence conflicts or is thin, lower confidence or use hold.
- News headlines are directional hints only, never guarantees.
- take_profit and stop_loss must be absolute price levels in the market's own quote currency,
  consistent with 'last'. Use null for both if action is hold.
- timeframe_hint is a short human string like "a few hours" or "1-2 days" — an estimate, not a promise.
- Never claim guaranteed profit; this is probabilistic analysis, not certainty.

Return JSON only, an array of up to 5 objects:
[{"market":"BTCUSDT","action":"buy|sell|hold","confidence":0.0,"reason":"...","take_profit":number|null,"stop_loss":number|null,"timeframe_hint":"..."}]

CANDIDATES
$rows
""".trimIndent()

        val body = JSONObject().apply {
            put("model", OPENAI_MODEL)
            put("input", prompt)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENAI_URL)
            .header("Authorization", "Bearer ${openAiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val responseText = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OpenAI HTTP ${response.code}: ${text.take(300)}")
            text
        }
        val outputText = extractOutputText(JSONObject(responseText))
        val arr = org.json.JSONArray(cleanJson(outputText))
        val result = ArrayList<Opportunity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            result += Opportunity(
                market = o.optString("market"),
                action = o.optString("action", "hold").lowercase(Locale.US),
                confidence = o.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                reason = o.optString("reason"),
                takeProfit = if (o.isNull("take_profit")) null else o.optDouble("take_profit"),
                stopLoss = if (o.isNull("stop_loss")) null else o.optDouble("stop_loss"),
                timeframeHint = o.optString("timeframe_hint"),
            )
        }
        return result
    }

    fun approvePaper(proposal: Proposal): PaperExecution {
        require(proposal.status == "pending") { "Proposal is not pending approval" }
        require(proposal.confidence >= MIN_CONFIDENCE) { "Risk gate rejected low-confidence proposal" }
        require(proposal.quoteAmount in 0.0..MAX_PAPER_QUOTE) { "Risk gate rejected quote amount" }
        require(proposal.action == "buy" || proposal.action == "sell") { "Only buy/sell proposals can be executed" }
        proposal.status = "approved_paper"
        return PaperExecution(
            proposalId = proposal.id,
            market = proposal.market,
            action = proposal.action,
            quoteAmount = proposal.quoteAmount,
            reference = "paper-${System.currentTimeMillis()}",
        )
    }

    private fun format(value: Double?): String = value?.let {
        if (it.isFinite()) String.format(Locale.US, "%.8f", it).trimEnd('0').trimEnd('.') else "—"
    } ?: "—"

    private fun extractOutputText(root: JSONObject): String {
        val direct = root.optString("output_text", "")
        if (direct.isNotBlank()) return direct
        val output = root.optJSONArray("output") ?: error("OpenAI response did not contain output")
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) return text
            }
        }
        error("OpenAI response did not contain text")
    }

    private fun cleanJson(text: String): String = text.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
