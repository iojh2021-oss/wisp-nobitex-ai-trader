package ai.wisp.trader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val NOBITEX_BASE_URL = "https://apiv2.nobitex.ir"
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

    fun fetchMarket(market: String, nobitexToken: String): MarketSnapshot {
        val normalized = market.trim().uppercase(Locale.US)
        require(normalized.matches(Regex("[A-Z0-9]+"))) { "Invalid market" }
        val src = normalized.removeSuffix("IRT").removeSuffix("USDT")
        val dst = if (normalized.endsWith("USDT")) "usdt" else "rls"
        val stats = get("$NOBITEX_BASE_URL/market/stats?srcCurrency=${src.lowercase()}&dstCurrency=$dst", nobitexToken)
        val book = get("$NOBITEX_BASE_URL/v3/orderbook/$normalized", nobitexToken)
        val s = JSONObject(stats)
        val b = JSONObject(book)
        val marketStats = s.optJSONObject(normalized) ?: s.optJSONObject(normalized.lowercase()) ?: s
        val bids = b.optJSONArray("bids")
        val asks = b.optJSONArray("asks")
        return MarketSnapshot(
            market = normalized,
            lastPrice = firstNonBlank(marketStats, "lastTradePrice", "last", "lastPrice"),
            high = firstNonBlank(marketStats, "dayHigh", "high", "highPrice"),
            low = firstNonBlank(marketStats, "dayLow", "low", "lowPrice"),
            volume = firstNonBlank(marketStats, "volume", "dayVolume"),
            bid = firstLevelPrice(bids),
            ask = firstLevelPrice(asks),
            rawStats = stats,
            rawOrderbook = book,
        )
    }

    fun analyze(snapshot: MarketSnapshot, openAiKey: String): Proposal {
        require(openAiKey.isNotBlank()) { "OpenAI API key is required for ChatGPT analysis" }
        val prompt = """
You are the decision engine for a paper-trading crypto assistant. Analyze ONLY the supplied market snapshot. Do not claim certainty and do not invent missing data. Return JSON only with exactly these fields:
{"action":"buy|sell|hold","quote_amount":number,"confidence":number,"reason":"short explanation"}
Rules: confidence must be 0..1. quote_amount is in quote currency. Prefer hold when evidence is weak, conflicting, stale, or incomplete. Never suggest more than 1000000 quote units. This is paper trading only.
Market snapshot:
market=${snapshot.market}
last=${snapshot.lastPrice}
high=${snapshot.high}
low=${snapshot.low}
volume=${snapshot.volume}
bid=${snapshot.bid}
ask=${snapshot.ask}
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

    private fun get(url: String, token: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "WispTrader/0.2")
            .get()
        if (token.isNotBlank()) builder.header("Authorization", "Token ${token.trim()}")
        return client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Nobitex HTTP ${response.code}: ${text.take(300)}")
            text
        }
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)
            if (value != null && value != JSONObject.NULL && value.toString().isNotBlank()) return value.toString()
        }
        return "—"
    }

    private fun firstLevelPrice(levels: JSONArray?): String {
        if (levels == null || levels.length() == 0) return "—"
        val first = levels.opt(0)
        return when (first) {
            is JSONArray -> first.optString(0, "—")
            is JSONObject -> firstNonBlank(first, "price")
            else -> first?.toString() ?: "—"
        }
    }

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
