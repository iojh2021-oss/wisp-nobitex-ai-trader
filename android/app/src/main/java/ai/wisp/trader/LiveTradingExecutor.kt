package ai.wisp.trader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

data class LiveExecution(
    val proposalId: String,
    val market: String,
    val action: String,
    val quoteAmount: Double,
    val nobitexOrderId: String,
)

/**
 * Places a REAL order on Nobitex. This spends real money. Kept as a
 * separate class from LocalTradingEngine (paper-only) so paper trading
 * behavior is never accidentally changed.
 */
class LiveTradingExecutor(private val client: OkHttpClient) {

    fun approveLive(
        proposal: LocalTradingEngine.Proposal,
        nobitexToken: String,
        confirmPhrase: String,
    ): LiveExecution {
        require(proposal.status == "pending") { "Proposal is not pending approval" }
        require(proposal.confidence >= 0.70) { "Risk gate rejected low-confidence proposal" }
        require(proposal.quoteAmount in 0.0..1_000_000.0) { "Risk gate rejected quote amount" }
        require(proposal.action == "buy" || proposal.action == "sell") { "Only buy/sell proposals can be executed" }
        require(nobitexToken.isNotBlank()) { "Nobitex API token is required for live trading" }

        val expectedPhrase = "CONFIRM LIVE ${proposal.market}"
        require(confirmPhrase == expectedPhrase) { "Confirmation phrase mismatch; type exactly: $expectedPhrase" }

        val (src, dst) = splitMarket(proposal.market)
        val payload = JSONObject().apply {
            put("type", proposal.action)
            put("srcCurrency", src)
            put("dstCurrency", dst)
            put("amount", String.format(Locale.US, "%.8f", proposal.quoteAmount))
            put("clientOrderId", proposal.id)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.nobitex.ir/market/orders/add")
            .header("Authorization", "Token ${nobitexToken.trim()}")
            .header("Content-Type", "application/json")
            .post(payload)
            .build()

        val responseText = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Nobitex HTTP ${response.code}: ${text.take(300)}")
            text
        }

        val json = JSONObject(responseText)
        if (json.optString("status") != "ok") {
            error("Nobitex rejected the order: ${json.optString("message", "unknown error")}")
        }

        proposal.status = "approved_live"
        return LiveExecution(
            proposalId = proposal.id,
            market = proposal.market,
            action = proposal.action,
            quoteAmount = proposal.quoteAmount,
            nobitexOrderId = json.optString("orderId", "unknown"),
        )
    }

    private fun splitMarket(market: String): Pair<String, String> {
        for (quote in listOf("USDT", "IRT", "RLS")) {
            if (market.length > quote.length && market.endsWith(quote)) {
                return market.dropLast(quote.length) to quote
            }
        }
        error("Could not split market $market into src/dst currency")
    }
}
