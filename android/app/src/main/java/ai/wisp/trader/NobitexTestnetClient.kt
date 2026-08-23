package ai.wisp.trader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/** Dedicated sandbox-only client. It cannot target the production Nobitex host. */
class NobitexTestnetClient(
    private val client: OkHttpClient,
    token: String
) {
    companion object {
        private const val BASE_URL = "https://testnetapi.nobitex.ir"
    }

    private val auth = "Token ${token.trim()}"

    fun addLimitOrder(
        symbol: String,
        side: String,
        amount: Double,
        price: Double
    ): String {
        require(side == "buy" || side == "sell") { "Invalid testnet order side" }
        require(amount > 0.0 && price > 0.0) { "Invalid testnet amount or price" }

        val normalized = symbol.uppercase(Locale.US)
        val src = normalized.removeSuffix("IRT").removeSuffix("USDT").lowercase(Locale.US)
        val dst = if (normalized.endsWith("USDT")) "usdt" else "rls"
        val clientOrderId = "wisp-${System.currentTimeMillis()}"

        val payload = JSONObject().apply {
            put("type", side)
            put("srcCurrency", src)
            put("dstCurrency", dst)
            put("amount", "%.12f".format(Locale.US, amount).trimEnd('0').trimEnd('.'))
            put("price", price)
            put("clientOrderId", clientOrderId.take(32))
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/market/orders/add")
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .post(payload)
            .build()

        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Nobitex Testnet HTTP ${response.code}: ${text.take(300)}")
            }
            val root = JSONObject(text)
            if (root.optString("status") != "ok") {
                error(root.optString("message", "Testnet rejected the order"))
            }
            val order = root.optJSONObject("order")
            val id = order?.optString("id")?.takeIf { it.isNotBlank() }
            id ?: clientOrderId
        }
    }
}
