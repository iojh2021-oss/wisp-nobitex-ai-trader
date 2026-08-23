package ai.wisp.trader

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

private val ProBg = Color(0xFF080A0D)
private val ProSurface = Color(0xFF111419)
private val ProSurface2 = Color(0xFF171B21)
private val ProText = Color(0xFFF5F7FA)
private val ProMuted = Color(0xFF969DA8)
private val ProLine = Color(0xFF2A3038)
private val ProGreen = Color(0xFF43E38A)
private val ProRed = Color(0xFFFF6875)
private val ProPurple = Color(0xFFB7A1FF)

@Composable
fun ProfessionalTraderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val secrets = remember { SecureTokenStore(context.applicationContext) }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    val engine = remember { LocalTradingEngine(client) }
    val scope = rememberCoroutineScope()

    var market by remember { mutableStateOf("BTCIRT") }
    var openAiKey by remember { mutableStateOf(secrets.readOpenAiKey()) }
    var nobitexToken by remember { mutableStateOf(secrets.readNobitexToken()) }
    var snapshot by remember { mutableStateOf<LocalTradingEngine.MarketSnapshot?>(null) }
    var proposal by remember { mutableStateOf<LocalTradingEngine.Proposal?>(null) }
    var paperHistory by remember { mutableStateOf(emptyList<LocalTradingEngine.PaperExecution>()) }
    var testnetEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }

    fun save() {
        secrets.saveOpenAiKey(openAiKey)
        secrets.saveNobitexToken(nobitexToken)
    }

    fun refresh() {
        busy = true
        save()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { engine.fetchMarket(market, nobitexToken) } }
            result.onSuccess {
                snapshot = it
                status = "Live • Nobitex • ${it.market}"
            }.onFailure { status = "Nobitex error: ${it.message ?: "connection failed"}" }
            busy = false
        }
    }

    fun analyze() {
        val data = snapshot ?: run { status = "Refresh market data first"; return }
        if (openAiKey.isBlank()) { status = "OpenAI API key is required"; return }
        busy = true
        save()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { engine.analyze(data, openAiKey) } }
            result.onSuccess {
                proposal = it
                status = if (it.status == "pending") "AI proposal ready • Risk Gate passed" else "AI proposal blocked by Risk Gate"
            }.onFailure { status = "AI error: ${it.message ?: "analysis failed"}" }
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
                status = "Paper trade recorded • no exchange order sent"
            }.onFailure { status = "Risk Gate: ${it.message ?: "blocked"}" }
            busy = false
        }
    }

    fun executeTestnet() {
        val p = proposal ?: return
        val data = snapshot ?: return
        if (!testnetEnabled) { status = "Enable Testnet mode first"; return }
        if (nobitexToken.isBlank()) { status = "A Nobitex Testnet token is required"; return }
        if (p.status != "pending") { status = "Only Risk-Gate-approved proposals can reach Testnet"; return }
        if (p.confidence < 0.70) { status = "Testnet blocked: confidence below 70%"; return }
        busy = true
        save()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val price = if (p.action == "buy") data.ask.toDouble() else data.bid.toDouble()
                    val quote = p.quoteAmount.coerceAtMost(1_000_000.0)
                    val amount = quote / price
                    NobitexTestnetClient(client, nobitexToken).addLimitOrder(data.market, p.action, amount, price)
                }
            }
            result.onSuccess {
                proposal = p.copy(status = "executed_testnet")
                status = "Testnet order accepted • ID $it • production endpoint was not used"
            }.onFailure { status = "Testnet error: ${it.message ?: "order rejected"}" }
            busy = false
        }
    }

    LaunchedEffect(market) {
        if (market.length >= 5) {
            while (true) {
                refresh()
                delay(15_000)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(ProBg).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WISP TRADER", color = ProText, fontWeight = FontWeight.Bold)
                    Text("AI market intelligence", color = ProMuted, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = ::refresh, enabled = !busy) {
                    Icon(Icons.Outlined.Refresh, null, tint = Color.White)
                }
                IconButton(onClick = {}, enabled = false) {
                    Icon(Icons.Outlined.Settings, null, tint = Color.White.copy(alpha = .8f))
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("MARKET", color = ProMuted, style = MaterialTheme.typography.labelSmall)
                            Text(market, color = ProText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        FilterChip(selected = market == "BTCIRT", onClick = { market = "BTCIRT" }, label = { Text("BTC") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = market == "ETHIRT", onClick = { market = "ETHIRT" }, label = { Text("ETH") })
                    }
                    OutlinedTextField(
                        value = market,
                        onValueChange = { market = it.uppercase(Locale.US).filter(Char::isLetterOrDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Symbol") }
                    )
                }
            }
        }

        snapshot?.let { data ->
            item {
                val price = data.lastPrice.toDoubleOrNull()
                val previous = data.ema20.toDoubleOrNull()
                val positive = price != null && previous != null && price >= previous
                Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f)) {
                                Text("LAST PRICE", color = ProMuted, style = MaterialTheme.typography.labelSmall)
                                Text(data.lastPrice, color = ProText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(data.trend.uppercase(Locale.US), color = if (positive) ProGreen else ProRed, fontWeight = FontWeight.Bold)
                        }
                        PriceSparkline(data.recentCandles)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("BID", data.bid)
                            Metric("ASK", data.ask)
                            Metric("SPREAD", "${data.spreadPercent}%")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("RSI", data.rsi14)
                            Metric("EMA20", data.ema20)
                            Metric("EMA50", data.ema50)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoGraph, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Market intelligence", color = ProText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("OB IMBALANCE", data.orderBookImbalance)
                            Metric("BUY FLOW", data.tradeBuyRatio)
                            Metric("VOLATILITY", "${data.volatilityPercent}%")
                        }
                        Text("${data.candleCount} candles • recent trades ${data.recentTrades.split(';').size}", color = ProMuted)
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Psychology, null, tint = ProPurple)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ChatGPT Decision Engine", color = ProText, fontWeight = FontWeight.Bold)
                            Text("Structured analysis → deterministic Risk Gate", color = ProMuted, style = MaterialTheme.typography.labelMedium)
                        }
                        Icon(Icons.Outlined.Security, null, tint = ProGreen)
                    }
                    OutlinedTextField(
                        value = openAiKey,
                        onValueChange = { openAiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("OpenAI API key") }
                    )
                    Button(onClick = ::analyze, enabled = !busy && snapshot != null, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Analyzing…" else "Analyze market")
                    }
                }
            }
        }

        proposal?.let { p ->
            item {
                val actionColor = when (p.action) { "buy" -> ProGreen; "sell" -> ProRed; else -> ProMuted }
                Card(colors = CardDefaults.cardColors(containerColor = ProSurface2), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("AI SIGNAL", color = ProMuted, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.weight(1f))
                            Text(p.action.uppercase(Locale.US), color = actionColor, fontWeight = FontWeight.Black)
                        }
                        Text("Confidence ${(p.confidence * 100).toInt()}%", color = ProText, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { p.confidence.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(p.reason, color = ProText)
                        Text("Risk: ${p.status}", color = ProMuted)
                        if (p.status == "pending") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = ::approvePaper, modifier = Modifier.weight(1f), enabled = !busy) { Text("Paper") }
                                OutlinedButton(onClick = ::executeTestnet, modifier = Modifier.weight(1f), enabled = !busy && testnetEnabled) { Text("Testnet") }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Cloud, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Nobitex Testnet", color = ProText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = testnetEnabled, onCheckedChange = { testnetEnabled = it })
                    }
                    Text("Sandbox only • orders go to testnetapi.nobitex.ir, never the production exchange.", color = ProMuted)
                    OutlinedTextField(
                        value = nobitexToken,
                        onValueChange = { nobitexToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Nobitex Testnet token") }
                    )
                    if (testnetEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = ProGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Testnet is armed; explicit execution is still required.", color = ProText)
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Runtime", color = ProMuted, style = MaterialTheme.typography.labelSmall)
                    Text(status, color = ProText)
                    Text("Standalone Android • no Termux • HTTPS only", color = ProMuted)
                }
            }
        }

        if (paperHistory.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Recent paper trades", color = ProText, fontWeight = FontWeight.Bold)
                        paperHistory.takeLast(5).reversed().forEach {
                            Text("${it.action.uppercase(Locale.US)} ${it.market} • ${it.quoteAmount} • ${it.reference}", color = ProMuted)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, color = ProMuted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = ProText, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PriceSparkline(raw: String) {
    val values = remember(raw) {
        raw.split(';').mapNotNull { entry ->
            entry.split(',').firstOrNull { it.startsWith("c=") }?.substringAfter("c=")?.toDoubleOrNull()
        }
    }
    Box(Modifier.fillMaxWidth().height(130.dp).background(ProSurface2)) {
        if (values.size >= 2) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val min = values.minOrNull() ?: 0.0
                val max = values.maxOrNull() ?: 1.0
                val range = (max - min).takeIf { it > 0 } ?: 1.0
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = size.width * index / (values.lastIndex.coerceAtLeast(1))
                    val y = size.height - ((value - min) / range).toFloat() * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
            }
        } else {
            Text("Chart waiting for candle data", color = ProMuted, modifier = Modifier.align(Alignment.Center))
        }
    }
}
