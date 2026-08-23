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

    fun fetchMarket(
        market: String,
        nobitexToken: String,
        baseUrl: String = "https://api.nobitex.ir",
    ): MarketSnapshot {
        val repository = NobitexMarketDataRepository(client, baseUrl = baseUrl)
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
