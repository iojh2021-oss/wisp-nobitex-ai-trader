package ai.wisp.trader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

private val MarketBackground = Color(0xFF090A0D)
private val MarketSurface = Color(0xFF111318)
private val MarketSurface2 = Color(0xFF171A20)
private val MarketBorder = Color(0xFF292D35)
private val MarketText = Color(0xFFF4F5F7)
private val MarketMuted = Color(0xFF9B9FAA)
private val MarketGreen = Color(0xFF46D38A)
private val MarketRed = Color(0xFFFF6F78)
private val MarketWhite = Color.White

@Composable
fun NobitexMarketTab(modifier: Modifier = Modifier) {
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    val repository = remember { NobitexMarketDataRepository(client) }
    val scope = rememberCoroutineScope()
    var symbol by remember { mutableStateOf("BTCIRT") }
    var data by remember { mutableStateOf<NobitexMarketDataRepository.MarketData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(true) }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.fetch(symbol) } }
                .onSuccess { data = it; error = null }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(symbol, autoRefresh) {
        if (autoRefresh) {
            while (true) {
                delay(15_000)
                refresh()
            }
        }
    }

    LazyColumn(
        modifier.fillMaxSize().background(MarketBackground).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF171A22), Color(0xFF0D0F14))))
                    .border(1.dp, MarketBorder, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NOBITEX MARKET", color = MarketMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text("Live Market Intelligence", color = MarketText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    }
                    val connected = data != null && error == null
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(Modifier.size(9.dp).clip(CircleShape).background(if (connected) MarketGreen else MarketMuted))
                        Spacer(Modifier.width(7.dp))
                        Text(if (connected) "LIVE" else "OFFLINE", color = MarketMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Public Nobitex data • HTTPS • 15s refresh • no API key required", color = MarketMuted, fontSize = 12.sp)
            }
        }
        item {
            MarketCard("MARKET") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it.uppercase(Locale.US).filter(Char::isLetterOrDigit) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Symbol") },
                        supportingText = { Text("Example: BTCIRT / BTCUSDT") }
                    )
                    OutlinedButton(onClick = ::refresh, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(5.dp))
                        Text("SYNC")
                    }
                }
                Button(
                    onClick = { autoRefresh = !autoRefresh },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (autoRefresh) MarketWhite else MarketSurface2, contentColor = if (autoRefresh) Color.Black else MarketText)
                ) { Text(if (autoRefresh) "LIVE REFRESH • ON" else "LIVE REFRESH • OFF") }
                error?.let { Text("Connection error: $it", color = MarketRed, fontSize = 12.sp) }
            }
        }
        data?.let { d ->
            item {
                MarketCard("PRICE") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(d.symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Last traded price", color = MarketMuted, fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(format(d.lastPrice), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            val trend = d.trend.lowercase(Locale.US)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (trend.contains("bull")) Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown, null, tint = if (trend.contains("bull")) MarketGreen else if (trend.contains("bear")) MarketRed else MarketMuted, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(trend.uppercase(Locale.US), color = MarketMuted, fontSize = 10.sp)
                            }
                        }
                    }
                    MarketMetricRow("Bid", format(d.bestBid), "Ask", format(d.bestAsk))
                    MarketMetricRow("24H change", "${format(d.dayChangePercent)}%", "Volume", format(d.volumeSrc))
                    MarketMetricRow("Day high", format(d.dayHigh), "Day low", format(d.dayLow))
                }
            }
            item {
                MarketCard("AI SIGNAL INPUT") {
                    MarketMetricRow("RSI14", format(d.rsi14), "EMA20", format(d.ema20))
                    MarketMetricRow("EMA50", format(d.ema50), "Volatility", "${format(d.volatilityPercent)}%")
                    MarketMetricRow("Spread", "${format(d.spreadPercent)}%", "Buy ratio", format(d.tradeBuyRatio))
                    MarketMetricRow("Book imbalance", format(d.orderBookImbalance), "Candles", d.candles.size.toString())
                    Text("These values are the live evidence layer consumed by the local trading engine before an AI proposal is generated.", color = MarketMuted, fontSize = 11.sp)
                }
            }
            item {
                MarketCard("ORDER BOOK") {
                    Text("BIDS", color = MarketGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    d.bids.take(8).forEach { Text("${format(it.price)}   ×   ${format(it.amount)}", fontSize = 12.sp) }
                    Spacer(Modifier.height(4.dp))
                    Text("ASKS", color = MarketRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    d.asks.take(8).forEach { Text("${format(it.price)}   ×   ${format(it.amount)}", fontSize = 12.sp) }
                }
            }
            item {
                MarketCard("RECENT TRADES") {
                    d.trades.takeLast(20).reversed().forEach {
                        val buy = it.type.equals("buy", true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(it.type.uppercase(Locale.US), color = if (buy) MarketGreen else MarketRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(format(it.price), fontSize = 11.sp)
                            Text(format(it.volume), color = MarketMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
            item {
                MarketCard("DATA STATUS") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = MarketGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Public read-only endpoints • last sync ${d.fetchedAtMs}", color = MarketMuted, fontSize = 10.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MarketCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MarketSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MarketBorder)
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = MarketMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            content()
        }
    }
}

@Composable
private fun MarketMetricRow(a: String, av: String, b: String, bv: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MarketMetric(a, av, Modifier.weight(1f))
        MarketMetric(b, bv, Modifier.weight(1f))
    }
}

@Composable
private fun MarketMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MarketSurface2).padding(11.dp)) {
        Text(label.uppercase(Locale.US), color = MarketMuted, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun format(value: Double?): String = value?.let {
    if (it.isFinite()) String.format(Locale.US, "%.8f", it).trimEnd('0').trimEnd('.') else "—"
} ?: "—"
