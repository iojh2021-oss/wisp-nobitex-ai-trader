package ai.wisp.trader

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches global market data and news from the CoinStats API
 * (https://openapiv1.coinstats.app), used as fundamental/context signal
 * alongside Nobitex's technical market data.
 */
class CoinStatsRepository(private val client: OkHttpClient) {

    data class CoinMarket(
        val id: String,
        val symbol: String,
        val name: String,
        val rank: Int,
        val price: Double,
        val priceChange1h: Double,
        val priceChange1d: Double,
        val priceChange1w: Double,
        val marketCap: Double,
        val volume: Double,
    )

    data class NewsItem(
        val title: String,
        val source: String,
        val publishedAt: String,
        val link: String,
    )

    private fun get(url: String, apiKey: String): String {
        val request = Request.Builder()
            .url(url)
            .header("X-API-KEY", apiKey.trim())
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("CoinStats HTTP ${response.code}: ${text.take(300)}")
            text
        }
    }

    fun fetchTopCoins(apiKey: String, limit: Int = 20): List<CoinMarket> {
        val text = get("https://openapiv1.coinstats.app/coins?limit=$limit", apiKey)
        val result = JSONObject(text).optJSONArray("result") ?: JSONArray()
        return (0 until result.length()).mapNotNull { i ->
            runCatching {
                val c = result.getJSONObject(i)
                CoinMarket(
                    id = c.optString("id"),
                    symbol = c.optString("symbol"),
                    name = c.optString("name"),
                    rank = c.optInt("rank"),
                    price = c.optDouble("price"),
                    priceChange1h = c.optDouble("priceChange1h"),
                    priceChange1d = c.optDouble("priceChange1d"),
                    priceChange1w = c.optDouble("priceChange1w"),
                    marketCap = c.optDouble("marketCap"),
                    volume = c.optDouble("volume"),
                )
            }.getOrNull()
        }
    }

    fun fetchNews(apiKey: String, limit: Int = 15): List<NewsItem> {
        val text = get("https://openapiv1.coinstats.app/news?limit=$limit", apiKey)
        val json = JSONObject(text)
        val arr = json.optJSONArray("news") ?: json.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val n = arr.getJSONObject(i)
                NewsItem(
                    title = n.optString("title"),
                    source = n.optString("source"),
                    publishedAt = n.optString("feedDate").ifBlank { n.optString("publishedAt") },
                    link = n.optString("link"),
                )
            }.getOrNull()
        }
    }
}
