package ai.wisp.trader

import okhttp3.OkHttpClient
import java.util.Locale

data class AutopilotPosition(
    val id: String,
    val market: String,
    val entryPrice: Double,
    val quoteAmount: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val openedAtMs: Long,
    val status: String = "open", // open | closed_profit | closed_loss
    val exitPrice: Double? = null,
    val exitAtMs: Long? = null,
    val reason: String = "",
)

/**
 * Runs a simple autonomous PAPER-only trading loop: periodically checks open
 * positions against their take-profit/stop-loss, and opens new simulated
 * positions from the AI opportunity scanner when a slot is free. No real
 * money is ever involved — this never calls approveLive or any live
 * executor, and never touches Nobitex order endpoints.
 */
class AutopilotEngine(private val client: OkHttpClient) {
    private val engine = LocalTradingEngine(client)
    private val marketRepo = NobitexMarketDataRepository(client)

    /** Checks all open positions against current prices, closing any that hit TP or SL. */
    fun checkPositions(positions: List<AutopilotPosition>): List<AutopilotPosition> {
        val hasOpen = positions.any { it.status == "open" }
        if (!hasOpen) return positions
        val stats = runCatching { marketRepo.fetchAllStats() }.getOrDefault(emptyList())
        val priceByMarket = stats.associateBy({ it.market }, { it.lastPrice })

        return positions.map { pos ->
            if (pos.status != "open") return@map pos
            val price = priceByMarket[pos.market] ?: return@map pos
            when {
                price >= pos.takeProfit -> pos.copy(
                    status = "closed_profit", exitPrice = price, exitAtMs = System.currentTimeMillis(),
                    reason = "Take-profit hit at ${format(price)}"
                )
                price <= pos.stopLoss -> pos.copy(
                    status = "closed_loss", exitPrice = price, exitAtMs = System.currentTimeMillis(),
                    reason = "Stop-loss hit at ${format(price)}"
                )
                else -> pos
            }
        }
    }

    /**
     * If there is a free slot, scans for the best new "buy" opportunity
     * (not already held) and opens a simulated position for it. Returns
     * null if no slot is free or nothing qualified.
     */
    fun tryOpenPosition(
        openAiKey: String,
        coinStatsApiKey: String,
        existing: List<AutopilotPosition>,
        maxPositions: Int,
        positionSizeQuote: Double,
    ): AutopilotPosition? {
        val openCount = existing.count { it.status == "open" }
        if (openCount >= maxPositions) return null
        if (openAiKey.isBlank()) return null

        val heldMarkets = existing.filter { it.status == "open" }.map { it.market }.toSet()
        val opportunities = runCatching { engine.scanOpportunities(openAiKey, coinStatsApiKey, topN = 20) }
            .getOrDefault(emptyList())

        val pick = opportunities.firstOrNull {
            it.action == "buy" && it.market !in heldMarkets && it.confidence >= 0.70
        } ?: return null

        val stats = runCatching { marketRepo.fetchAllStats() }.getOrDefault(emptyList())
        val entryPrice = stats.firstOrNull { it.market == pick.market }?.lastPrice ?: return null

        val stopLoss = pick.stopLoss ?: (entryPrice * 0.99)
        val takeProfit = pick.takeProfit ?: (entryPrice * 1.02)
        if (takeProfit <= entryPrice || stopLoss >= entryPrice) return null

        return AutopilotPosition(
            id = "auto-${System.currentTimeMillis()}",
            market = pick.market,
            entryPrice = entryPrice,
            quoteAmount = positionSizeQuote,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            openedAtMs = System.currentTimeMillis(),
            reason = pick.reason,
        )
    }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
}
