package ai.wisp.trader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

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
        loading = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.fetch(symbol) }
            }.onSuccess {
                data = it
                error = null
            }.onFailure {
                error = it.message ?: it.javaClass.simpleName
            }
            loading = false
        }
    }

    LaunchedEffect(symbol, autoRefresh) {
        if (autoRefresh) {
            while (true) {
                refresh()
                delay(15_000)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nobitex Market Data", style = MaterialTheme.typography.headlineSmall)
                Text("Live public data • HTTPS • 15s refresh", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = symbol,
                            onValueChange = { symbol = it.uppercase(Locale.US).filter(Char::isLetterOrDigit) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Market") }
                        )
                        IconButton(onClick = ::refresh, enabled = !loading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                    Button(onClick = { autoRefresh = !autoRefresh }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (autoRefresh) "Auto refresh: ON" else "Auto refresh: OFF")
                    }
                    error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        data?.let { d ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(d.symbol, style = MaterialTheme.typography.titleLarge)
                        Text("Last: ${d.lastPrice ?: "—"}")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Bid: ${d.bestBid ?: "—"}", Modifier.weight(1f))
                            Text("Ask: ${d.bestAsk ?: "—"}", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("24h: ${d.dayChangePercent ?: "—"}%", Modifier.weight(1f))
                            Text("Volume: ${d.volumeSrc ?: "—"}", Modifier.weight(1f))
                        }
                        Text("Trend: ${d.trend}")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Analysis indicators", style = MaterialTheme.typography.titleLarge)
                        Text("RSI14: ${d.rsi14 ?: "—"}")
                        Text("EMA20: ${d.ema20 ?: "—"}")
                        Text("EMA50: ${d.ema50 ?: "—"}")
                        Text("Volatility: ${d.volatilityPercent ?: "—"}%")
                        Text("Order-book imbalance: ${d.orderBookImbalance ?: "—"}")
                        Text("Buy ratio: ${d.tradeBuyRatio ?: "—"}")
                        Text("Spread: ${d.spreadPercent ?: "—"}%")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Order book", style = MaterialTheme.typography.titleLarge)
                        d.bids.take(5).forEach { Text("BID  ${it.price}  ×  ${it.amount}") }
                        d.asks.take(5).forEach { Text("ASK  ${it.price}  ×  ${it.amount}") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Recent trades", style = MaterialTheme.typography.titleLarge)
                        d.trades.takeLast(10).reversed().forEach {
                            Text("${it.type.uppercase(Locale.US)}  ${it.price}  ×  ${it.volume}")
                        }
                    }
                }
            }
            item {
                Text(
                    "These public endpoints feed the analysis layer. Live order execution is intentionally not enabled by this screen; use the Risk Gate/Paper Trading flow for automated testing.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
