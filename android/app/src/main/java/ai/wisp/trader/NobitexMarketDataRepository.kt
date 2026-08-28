package ai.wisp.trader

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Read-only Nobitex public market-data layer.
 * No API token is required for these endpoints.
 *
 * Sources:
 * - /market/stats
 * - /v3/orderbook/:symbol
 * - /v2/depth/:symbol
 * - /v2/trades/:symbol
 * - /market/udf/history
 */
class NobitexMarketDataRepository(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://apiv2.nobitex.ir"
) {
    data class Level(val price: Double, val amount: Double)

    data class Trade(
        val time: Long,
        val price: Double,
        val volume: Double,
        val type: String
    )

    data class Candle(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double
    )

    data class MarketData(
        val symbol: String,
        val bestBid: Double?,
        val bestAsk: Double?,
        val lastPrice: Double?,
        val dayOpen: Double?,
        val dayHigh: Double?,
        val dayLow: Double?,
        val dayChangePercent: Double?,
        val volumeSrc: Double?,
        val bids: List<Level>,
        val asks: List<Level>,
        val depthBids: List<Level>,
        val depthAsks: List<Level>,
        val trades: List<Trade>,
        val candles: List<Candle>,
        val spreadPercent: Double?,
        val orderBookImbalance: Double?,
        val tradeBuyRatio: Double?,
        val rsi14: Double?,
        val ema20: Double?,
        val ema50: Double?,
        val volatilityPercent: Double?,
        val trend: String,
        val fetchedAtMs: Long
    )

    fun fetch(symbolInput: String, resolution: String = "15", candleCount: Int = 100): MarketData {
        val symbol = normalizeSymbol(symbolInput)
        val src = symbol.removeSuffix("IRT").removeSuffix("USDT").lowercase(Locale.US)
        val dst = if (symbol.endsWith("USDT")) "usdt" else "rls"

        val statsText = get("/market/stats") {
            addQueryParameter("srcCurrency", src)
            addQueryParameter("dstCurrency", dst)
        }
        val orderbookText = get("/v3/orderbook/$symbol")
        val depthText = get("/v2/depth/$symbol")
        val tradesText = get("/v2/trades/$symbol")
        val nowSec = System.currentTimeMillis() / 1000L
        val ohlcText = get("/market/udf/history") {
            addQueryParameter("symbol", symbol)
            addQueryParameter("resolution", resolution)
            addQueryParameter("to", nowSec.toString())
            addQueryParameter("countback", candleCount.coerceIn(20, 200).toString())
        }

        val stats = parseStats(statsText, symbol)
        val orderbook = parseLevels(JSONObject(orderbookText))
        val depth = parseLevels(JSONObject(depthText))
        val trades = parseTrades(JSONObject(tradesText))
        val candles = parseCandles(JSONObject(ohlcText))

        val bestBid = orderbook.bids.firstOrNull()?.price ?: stats.bestBid
        val bestAsk = orderbook.asks.firstOrNull()?.price ?: stats.bestAsk
        val last = stats.lastPrice ?: candles.lastOrNull()?.close
        val ema20 = ema(candles.map { it.close }, 20)
        val ema50 = ema(candles.map { it.close }, 50)
        val rsi14 = rsi(candles.map { it.close }, 14)
        val volatility = volatilityPercent(candles)
        val imbalance = orderBookImbalance(orderbook.bids.take(20), orderbook.asks.take(20))
        val buyRatio = tradeBuyRatio(trades)
        val trend = classifyTrend(last, ema20, ema50, rsi14, imbalance, buyRatio)
        val spread = if (bestBid != null && bestAsk != null && bestBid > 0.0) {
            ((bestAsk - bestBid) / bestBid) * 100.0
        } else null

        return MarketData(
            symbol = symbol,
            bestBid = bestBid,
            bestAsk = bestAsk,
            lastPrice = last,
            dayOpen = stats.dayOpen,
            dayHigh = stats.dayHigh,
            dayLow = stats.dayLow,
            dayChangePercent = stats.dayChangePercent,
            volumeSrc = stats.volumeSrc,
            bids = orderbook.bids,
            asks = orderbook.asks,
            depthBids = depth.bids,
            depthAsks = depth.asks,
            trades = trades,
            candles = candles,
            spreadPercent = spread,
            orderBookImbalance = imbalance,
            tradeBuyRatio = buyRatio,
            rsi14 = rsi14,
            ema20 = ema20,
            ema50 = ema50,
            volatilityPercent = volatility,
            trend = trend,
            fetchedAtMs = System.currentTimeMillis()
        )
    }

    private data class Stats(
        val bestBid: Double?, val bestAsk: Double?, val lastPrice: Double?,
        val dayOpen: Double?, val dayHigh: Double?, val dayLow: Double?,
        val dayChangePercent: Double?, val volumeSrc: Double?
    )

    private data class Levels(val bids: List<Level>, val asks: List<Level>)

    private fun parseStats(text: String, symbol: String): Stats {
        val root = JSONObject(text)
        val stats = root.optJSONObject("stats") ?: root
        val key = symbol.lowercase(Locale.US).let { s ->
            if (stats.has(s)) s else s.removeSuffix("irt").removeSuffix("usdt") + "-" +
                if (symbol.endsWith("USDT")) "usdt" else "rls"
        }
        val obj = stats.optJSONObject(key) ?: stats.optJSONObject(symbol) ?: stats
        return Stats(
            bestBid = number(obj, "bestBuy"),
            bestAsk = number(obj, "bestSell"),
            lastPrice = number(obj, "latest", "lastTradePrice", "last"),
            dayOpen = number(obj, "dayOpen"),
            dayHigh = number(obj, "dayHigh"),
            dayLow = number(obj, "dayLow"),
            dayChangePercent = number(obj, "dayChange"),
            volumeSrc = number(obj, "volumeSrc", "volume")
        )
    }

    private fun parseLevels(root: JSONObject): Levels {
        return Levels(
            bids = parseLevelArray(root.optJSONArray("bids")),
            asks = parseLevelArray(root.optJSONArray("asks"))
        )
    }

    private fun parseLevelArray(array: JSONArray?): List<Level> {
        if (array == null) return emptyList()
        val result = ArrayList<Level>(array.length())
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            when (item) {
                is JSONArray -> {
                    val price = item.optString(0).toDoubleOrNull()
                    val amount = item.optString(1).toDoubleOrNull()
                    if (price != null && amount != null && price > 0 && amount >= 0) {
                        result += Level(price, amount)
                    }
                }
                is JSONObject -> {
                    val price = number(item, "price")
                    val amount = number(item, "amount", "volume", "quantity")
                    if (price != null && amount != null && price > 0 && amount >= 0) {
                        result += Level(price, amount)
                    }
                }
            }
        }
        return result
    }

    private fun parseTrades(root: JSONObject): List<Trade> {
        val array = root.optJSONArray("trades") ?: return emptyList()
        val result = ArrayList<Trade>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val price = number(item, "price") ?: continue
            val volume = number(item, "volume", "amount") ?: 0.0
            val time = item.optLong("time", 0L)
            result += Trade(time, price, volume, item.optString("type", "unknown").lowercase(Locale.US))
        }
        return result
    }

    private fun parseCandles(root: JSONObject): List<Candle> {
        if (root.optString("s") != "ok") return emptyList()
        val t = root.optJSONArray("t") ?: return emptyList()
        val o = root.optJSONArray("o") ?: return emptyList()
        val h = root.optJSONArray("h") ?: return emptyList()
        val l = root.optJSONArray("l") ?: return emptyList()
        val c = root.optJSONArray("c") ?: return emptyList()
        val v = root.optJSONArray("v") ?: return emptyList()
        val n = minOf(t.length(), o.length(), h.length(), l.length(), c.length(), v.length())
        val result = ArrayList<Candle>(n)
        for (i in 0 until n) {
            result += Candle(
                time = t.optLong(i),
                open = o.optDouble(i),
                high = h.optDouble(i),
                low = l.optDouble(i),
                close = c.optDouble(i),
                volume = v.optDouble(i)
            )
        }
        return result
    }

    private fun orderBookImbalance(bids: List<Level>, asks: List<Level>): Double? {
        val bid = bids.sumOf { it.amount }
        val ask = asks.sumOf { it.amount }
        val total = bid + ask
        return if (total > 0) (bid - ask) / total else null
    }

    private fun tradeBuyRatio(trades: List<Trade>): Double? {
        if (trades.isEmpty()) return null
        val buy = trades.filter { it.type == "buy" }.sumOf { it.volume }
        val sell = trades.filter { it.type == "sell" }.sumOf { it.volume }
        val total = buy + sell
        return if (total > 0) buy / total else null
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val k = 2.0 / (period + 1)
        var value = values.take(period).average()
        for (i in period until values.size) value = values[i] * k + value * (1 - k)
        return value
    }

    private fun rsi(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val delta = values[i] - values[i - 1]
            if (delta >= 0) gain += delta else loss -= delta
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until values.size) {
            val delta = values[i] - values[i - 1]
            val g = max(delta, 0.0)
            val l = max(-delta, 0.0)
            avgGain = ((avgGain * (period - 1)) + g) / period
            avgLoss = ((avgLoss * (period - 1)) + l) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun volatilityPercent(candles: List<Candle>): Double? {
        if (candles.size < 2) return null
        val returns = candles.zipWithNext().mapNotNull { (a, b) ->
            if (a.close > 0 && b.close > 0) (b.close / a.close) - 1.0 else null
        }
        if (returns.size < 2) return null
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        return kotlin.math.sqrt(variance) * 100.0
    }

    private fun classifyTrend(
        last: Double?, ema20: Double?, ema50: Double?, rsi14: Double?, imbalance: Double?, buyRatio: Double?
    ): String {
        if (last == null || ema20 == null || ema50 == null) return "insufficient_data"
        var score = 0
        if (last > ema20) score++ else score--
        if (ema20 > ema50) score++ else score--
        if ((rsi14 ?: 50.0) > 55) score++ else if ((rsi14 ?: 50.0) < 45) score--
        if ((imbalance ?: 0.0) > 0.10) score++ else if ((imbalance ?: 0.0) < -0.10) score--
        if ((buyRatio ?: 0.5) > 0.58) score++ else if ((buyRatio ?: 0.5) < 0.42) score--
        return when {
            score >= 3 -> "bullish"
            score <= -3 -> "bearish"
            else -> "neutral"
        }
    }

    private fun number(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val value = obj.opt(key) ?: continue
            val parsed = value.toString().toDoubleOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun normalizeSymbol(input: String): String {
        val symbol = input.trim().uppercase(Locale.US)
        require(symbol.matches(Regex("[A-Z0-9]+"))) { "Invalid Nobitex market symbol" }
        require(symbol.endsWith("IRT") || symbol.endsWith("USDT")) {
            "Nobitex symbol must end with IRT or USDT"
        }
        return symbol
    }

    private fun get(path: String, configure: okhttp3.HttpUrl.Builder.() -> Unit = {}): String {
        val url = (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder().apply(configure).build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WispTrader/0.6")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Nobitex HTTP ${response.code}: ${text.take(300)}")
            text
        }
    }
}
