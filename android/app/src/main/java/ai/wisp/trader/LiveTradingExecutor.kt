package ai.wisp.trader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.util.Base64
import java.util.Locale

data class LiveExecution(
    val proposalId: String,
    val market: String,
    val action: String,
    val quoteAmount: Double,
    val nobitexOrderId: String,
    val authMode: String,
)

/**
 * Places a REAL order on Nobitex. This spends real money. Supports two
 * auth methods, auto-selected based on which credentials are supplied:
 *  - Signed "API Key" (recommended): Nobitex-Key / Nobitex-Signature /
 *    Nobitex-Timestamp headers, Ed25519 signature over
 *    timestamp+METHOD+path+body.
 *  - Legacy plain token: Authorization: Token <token>. Kept intentionally
 *    for accounts still using the older auth style.
 */
class LiveTradingExecutor(private val client: OkHttpClient) {

    fun approveLive(
        proposal: LocalTradingEngine.Proposal,
        nobitexApiKey: String,
        nobitexPrivateKeyBase64: String,
        nobitexLegacyToken: String,
        confirmPhrase: String,
    ): LiveExecution {
        require(proposal.status == "pending") { "Proposal is not pending approval" }
        require(proposal.confidence >= 0.70) { "Risk gate rejected low-confidence proposal" }
        require(proposal.quoteAmount in 0.0..1_000_000.0) { "Risk gate rejected quote amount" }
        require(proposal.action == "buy" || proposal.action == "sell") { "Only buy/sell proposals can be executed" }

        val useSignedAuth = nobitexApiKey.isNotBlank() && nobitexPrivateKeyBase64.isNotBlank()
        require(useSignedAuth || nobitexLegacyToken.isNotBlank()) {
            "Provide either a Nobitex API Key + Private Key (recommended) or a legacy API token"
        }

        val expectedPhrase = "CONFIRM LIVE ${proposal.market}"
        require(confirmPhrase == expectedPhrase) { "Confirmation phrase mismatch; type exactly: $expectedPhrase" }

        val (src, dst) = splitMarket(proposal.market)
        val bodyJson = JSONObject().apply {
            put("type", proposal.action)
            put("srcCurrency", src)
            put("dstCurrency", dst)
            put("amount", String.format(Locale.US, "%.8f", proposal.quoteAmount))
            put("clientOrderId", proposal.id)
        }.toString()

        val path = "/market/orders/add"
        val requestBuilder = Request.Builder()
            .url("https://apiv2.nobitex.ir$path")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))

        val authMode: String
        if (useSignedAuth) {
            authMode = "signed_api_key"
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val payload = timestamp + "POST" + path + bodyJson
            val signature = signEd25519(nobitexPrivateKeyBase64, payload)
            requestBuilder
                .header("Nobitex-Key", nobitexApiKey.trim())
                .header("Nobitex-Signature", signature)
                .header("Nobitex-Timestamp", timestamp)
        } else {
            authMode = "legacy_token"
            requestBuilder.header("Authorization", "Token ${nobitexLegacyToken.trim()}")
        }

        val responseText = client.newCall(requestBuilder.build()).execute().use { response ->
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
            authMode = authMode,
        )
    }

    private fun signEd25519(privateKeyBase64: String, payload: String): String {
        val seed = decodeBase64UrlAny(privateKeyBase64)
        require(seed.size == 32) { "Nobitex private key must decode to a 32-byte Ed25519 seed" }
        val params = Ed25519PrivateKeyParameters(seed, 0)
        val signer = Ed25519Signer()
        signer.init(true, params)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(payloadBytes, 0, payloadBytes.size)
        val signature = signer.generateSignature()
        return Base64.getUrlEncoder().encodeToString(signature)
    }

    private fun decodeBase64UrlAny(s: String): ByteArray {
        val trimmed = s.trim()
        val padded = padBase64(trimmed)
        return try {
            Base64.getUrlDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            Base64.getDecoder().decode(padded)
        }
    }

    private fun padBase64(s: String): String {
        val remainder = s.length % 4
        return if (remainder == 0) s else s + "=".repeat(4 - remainder)
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
