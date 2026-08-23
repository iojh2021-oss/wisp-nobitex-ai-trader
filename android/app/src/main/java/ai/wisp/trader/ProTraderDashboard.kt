package ai.wisp.trader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val PUBLIC_URL = "https://api.nobitex.ir"
private const val TESTNET_URL = "https://testnetapi.nobitex.ir"

@Composable
fun ProTraderDashboard(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SecureTokenStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    val engine = remember { LocalTradingEngine(client) }

    var market by remember { mutableStateOf("BTCIRT") }
    var sourceTestnet by remember { mutableStateOf(false) }
    var openAiKey by remember { mutableStateOf(store.readOpenAiKey()) }
    var publicToken by remember { mutableStateOf(store.readNobitexToken()) }
    var testnetToken by remember { mutableStateOf(store.readNobitexTestnetToken()) }
    var snapshot by remember { mutableStateOf<LocalTradingEngine.MarketSnapshot?>(null) }
    var proposal by remember { mutableStateOf<LocalTradingEngine.Proposal?>(null) }
    var paperHistory by remember { mutableStateOf(emptyList<LocalTradingEngine.PaperExecution>()) }
    var status by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }

    fun persist() {
        store.saveOpenAiKey(openAiKey)
        store.saveNobitexToken(publicToken)
        store.saveNobitexTestnetToken(testnetToken)
    }

    fun refresh() {
        busy = true
        persist()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    engine.fetchMarket(
                        market = market,
                        nobitexToken = if (sourceTestnet) testnetToken else publicToken,
                        baseUrl = if (sourceTestnet) TESTNET_URL else PUBLIC_URL,
                    )
                }
            }
            result.onSuccess {
                snapshot = it
                proposal = null
                status = if (sourceTestnet) "Testnet market data connected" else "Nobitex market data connected"
            }.onFailure {
                status = "Connection failed: ${it.message ?: it.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun analyze() {
        val data = snapshot ?: run {
            status = "Refresh market data first"
            return
        }
        if (openAiKey.isBlank()) {
            status = "OpenAI API key is required for programmatic ChatGPT analysis"
            return
        }
        busy = true
        persist()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.analyze(data, openAiKey) }
            }
            result.onSuccess {
                proposal = it
                status = when (it.status) {
                    "pending" -> "AI proposal ready — human approval required"
                    else -> "Risk Gate blocked the AI proposal"
                }
            }.onFailure {
                status = "AI error: ${it.message ?: it.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun approvePaper() {
        val p = proposal ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { engine.approvePaper(p) } }
            result.onSuccess {
                paperHistory = paperHistory + it
                proposal = p.copy(status = "approved_paper")
                status = "Paper order recorded — no exchange order sent"
            }.onFailure {
                status = "Risk gate: ${it.message ?: it.javaClass.simpleName}"
            }
            busy = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroHeader(
                testnet = sourceTestnet,
                status = status,
                onRefresh = ::refresh,
                busy = busy,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Trading mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !sourceTestnet,
                            onClick = { sourceTestnet = false; status = "Public market mode" },
                            label = { Text("Public") },
                            leadingIcon = { Icon(Icons.Outlined.ShowChart, null) }
                        )
                        FilterChip(
                            selected = sourceTestnet,
                            onClick = { sourceTestnet = true; status = "Testnet mode" },
                            label = { Text("Testnet") },
                            leadingIcon = { Icon(Icons.Outlined.Security, null) }
                        )
                    }
                    Text(
                        if (sourceTestnet) "Sandbox endpoint • no production orders from this screen" else "Production market-data endpoint • read-only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Market & AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = market,
                        onValueChange = { market = it.uppercase(Locale.US).filter(Char::isLetterOrDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Market") },
                        supportingText = { Text("BTCIRT, ETHIRT, BTCUSDT…") }
                    )
                    Button(onClick = ::refresh, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Working…" else "Load live market data")
                    }
                    Button(
                        onClick = ::analyze,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && snapshot != null
                    ) {
                        Icon(Icons.Outlined.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ask ChatGPT to analyze")
                    }
                }
            }
        }

        snapshot?.let { data ->
            item {
                Text("Market intelligence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item {
                MetricGrid(data)
            }
            item {
                IndicatorCard(data)
            }
        }

        proposal?.let { p ->
            item { AiDecisionCard(p, onApprove = ::approvePaper) }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Secure connections", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(
                        value = openAiKey,
                        onValueChange = { openAiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("OpenAI API key") }
                    )
                    OutlinedTextField(
                        value = publicToken,
                        onValueChange = { publicToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Nobitex production token") },
                        supportingText = { Text("Only needed for authenticated account features; public market data needs no token.") }
                    )
                    OutlinedTextField(
                        value = testnetToken,
                        onValueChange = { testnetToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Nobitex testnet token") },
                        supportingText = { Text("Keep this separate from your production token.") }
                    )
                    OutlinedButton(onClick = { persist(); status = "Secrets saved in Android Keystore" }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save securely")
                    }
                }
            }
        }

        if (paperHistory.isNotEmpty()) {
            item { Text("Recent paper decisions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(paperHistory.reversed()) { execution ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${execution.action.uppercase(Locale.US)} • ${execution.market}", fontWeight = FontWeight.SemiBold)
                        Text("Quote: ${execution.quoteAmount} • ${execution.reference}")
                    }
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Text(
                "Safety policy: ChatGPT proposes; the deterministic Risk Gate decides whether a paper action may be approved. Testnet execution is isolated from production.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun HeroHeader(testnet: Boolean, status: String, onRefresh: () -> Unit, busy: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                Icon(Icons.Outlined.ShowChart, null, tint = Color.Black, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Wisp Trader", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (testnet) "AI + Nobitex Testnet" else "AI + Nobitex Market Intelligence", style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, null) }
        }
    }
}

@Composable
private fun MetricGrid(data: LocalTradingEngine.MarketSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Last", data.lastPrice, Modifier.weight(1f))
            MetricCard("Spread", "${data.spreadPercent}%", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Bid", data.bid, Modifier.weight(1f))
            MetricCard("Ask", data.ask, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("RSI 14", data.rsi14, Modifier.weight(1f))
            MetricCard("Trend", data.trend, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IndicatorCard(data: LocalTradingEngine.MarketSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI evidence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            EvidenceRow("EMA 20", data.ema20)
            EvidenceRow("EMA 50", data.ema50)
            EvidenceRow("Order-book imbalance", data.orderBookImbalance)
            EvidenceRow("Trade buy ratio", data.tradeBuyRatio)
            EvidenceRow("Volatility", "${data.volatilityPercent}%")
            EvidenceRow("Candles", data.candleCount.toString())
        }
    }
}

@Composable
private fun EvidenceRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AiDecisionCard(proposal: LocalTradingEngine.Proposal, onApprove: () -> Unit) {
    val confidence = proposal.confidence.coerceIn(0.0, 1.0).toFloat()
    val allowed = proposal.status == "pending"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ChatGPT decision", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                AssistChip(onClick = {}, enabled = false, label = { Text(proposal.status) })
            }
            Text("${proposal.action.uppercase(Locale.US)} • ${proposal.market}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Confidence ${(proposal.confidence * 100).toInt()}%")
            LinearProgressIndicator(progress = { confidence }, modifier = Modifier.fillMaxWidth())
            Text(proposal.reason)
            Text("Suggested quote amount: ${proposal.quoteAmount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (allowed) {
                Button(onClick = onApprove, modifier = Modifier.fillMaxWidth()) {
                    Text("Approve paper order")
                }
            } else {
                Text("No executable action is available because the Risk Gate blocked or completed this proposal.")
            }
        }
    }
}
