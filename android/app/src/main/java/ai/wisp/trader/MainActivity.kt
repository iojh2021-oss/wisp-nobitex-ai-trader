package ai.wisp.trader

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val CHATGPT_URL = "https://chatgpt.com/c/6a882026-cca0-83eb-9884-590944f19289"

private val WispBackground = Color(0xFF090A0D)
private val WispSurface = Color(0xFF111318)
private val WispSurface2 = Color(0xFF171A20)
private val WispBorder = Color(0xFF292D35)
private val WispText = Color(0xFFF4F5F7)
private val WispMuted = Color(0xFF9B9FAA)
private val WispWhite = Color.White
private val WispPurple = Color(0xFF8B6CFF)
private val WispGreen = Color(0xFF46D38A)
private val WispRed = Color(0xFFFF6F78)

private val WispColors = androidx.compose.material3.darkColorScheme(
    primary = WispWhite,
    onPrimary = Color.Black,
    background = WispBackground,
    onBackground = WispText,
    surface = WispSurface,
    onSurface = WispText,
    surfaceVariant = WispSurface2,
    onSurfaceVariant = WispMuted,
    outline = WispBorder,
    secondary = WispPurple,
    onSecondary = WispText
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WispTraderApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WispTraderApp() {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(
        colorScheme = WispColors,
        typography = MaterialTheme.typography.copy(
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
            headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
        )
    ) {
        Scaffold(
            containerColor = WispBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when (tab) {
                                    0 -> "WISP TRADER"
                                    1 -> "CHATGPT WORKSPACE"
                                    else -> "NOBITEX MARKET"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            )
                            Text("AI • MARKET • EXECUTION", fontSize = 9.sp, color = WispMuted, letterSpacing = 1.4.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = WispBackground)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0D0F13), tonalElevation = 0.dp) {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Outlined.ShowChart, null, tint = WispWhite) }, label = { Text("Trader") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = WispWhite) }, label = { Text("ChatGPT") })
                    NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Outlined.Public, null, tint = WispWhite) }, label = { Text("Market") })
                }
            }
        ) { pad ->
            when (tab) {
                0 -> TraderDashboard(Modifier.padding(pad))
                1 -> ChatGptWorkspace(Modifier.padding(pad))
                else -> NobitexMarketTab(Modifier.padding(pad))
            }
        }
    }
}

@Composable
private fun TraderDashboard(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secrets = remember { SecureTokenStore(context.applicationContext) }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    val engine = remember { LocalTradingEngine(client) }
    val liveEngine = remember { LiveTradingExecutor(client) }
    val scope = rememberCoroutineScope()
    var market by remember { mutableStateOf("BTCIRT") }
    var openAiKey by remember { mutableStateOf(secrets.readOpenAiKey()) }
    var nobitexToken by remember { mutableStateOf(secrets.readNobitexToken()) }
    var nobitexApiKey by remember { mutableStateOf(secrets.readNobitexApiKey()) }
    var nobitexPrivateKey by remember { mutableStateOf(secrets.readNobitexPrivateKey()) }
    var snapshot by remember { mutableStateOf<LocalTradingEngine.MarketSnapshot?>(null) }
    var proposal by remember { mutableStateOf<LocalTradingEngine.Proposal?>(null) }
    var executions by remember { mutableStateOf(emptyList<LocalTradingEngine.PaperExecution>()) }
    var status by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(false) }
    var showLiveConfirm by remember { mutableStateOf(false) }
    var liveConfirmText by remember { mutableStateOf("") }
    var liveExecutions by remember { mutableStateOf(emptyList<LiveExecution>()) }

    fun save() {
        secrets.saveOpenAiKey(openAiKey.trim())
        secrets.saveNobitexToken(nobitexToken.trim())
        secrets.saveNobitexApiKey(nobitexApiKey.trim())
        secrets.saveNobitexPrivateKey(nobitexPrivateKey.trim())
    }

    fun refresh() {
        if (busy) return
        busy = true
        save()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { engine.fetchMarket(market, nobitexToken) } }
                .onSuccess { snapshot = it; status = "LIVE • ${it.market}" }
                .onFailure { status = "Connection error • ${it.message ?: "unknown"}" }
            busy = false
        }
    }

    fun analyze() {
        val data = snapshot ?: run { status = "Load market data first"; return }
        if (openAiKey.isBlank()) { status = "OpenAI API key is required for AI analysis"; return }
        if (busy) return
        busy = true
        save()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { engine.analyze(data, openAiKey) } }
                .onSuccess { proposal = it; status = "AI analysis complete • Risk Gate applied" }
                .onFailure { status = "AI error • ${it.message ?: "unknown"}" }
            busy = false
        }
    }

    fun paperExecute() {
        val p = proposal ?: return
        if (busy) return
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { engine.approvePaper(p) } }
                .onSuccess { executions = executions + it; proposal = p.copy(status = "approved_paper"); status = "Paper execution completed" }
                .onFailure { status = "Risk Gate • ${it.message ?: "blocked"}" }
            busy = false
        }
    }

    fun liveExecute() {
        val p = proposal ?: return
        if (busy) return
        busy = true
        save()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { liveEngine.approveLive(p, nobitexApiKey, nobitexPrivateKey, nobitexToken, liveConfirmText) } }
                .onSuccess {
                    liveExecutions = liveExecutions + it
                    proposal = p.copy(status = "approved_live")
                    status = "LIVE order sent • ${it.nobitexOrderId} (${it.authMode})"
                    showLiveConfirm = false
                    liveConfirmText = ""
                }
                .onFailure { status = "Live order failed • ${it.message ?: "unknown"}" }
            busy = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(autoRefresh, market) {
        if (autoRefresh) {
            while (true) {
                refresh()
                delay(15_000)
            }
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF171A22), Color(0xFF0D0F14))))
                    .border(1.dp, WispBorder, RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (snapshot != null) WispGreen else WispMuted))
                        Spacer(Modifier.width(8.dp))
                        Text(if (snapshot != null) "MARKET CONNECTED" else "STANDBY", color = WispMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    }
                    Text("Wisp AI Trading", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Nobitex market intelligence with a guarded AI decision flow.", color = WispMuted, fontSize = 13.sp)
                }
            }
        }
        item {
            SectionCard("CONNECTION") {
                OutlinedTextField(market, { market = it.uppercase(Locale.US).filter(Char::isLetterOrDigit) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nobitex market") })
                OutlinedTextField(nobitexToken, { nobitexToken = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Nobitex API token") }, supportingText = { Text("Optional for public market data") })
                OutlinedTextField(nobitexApiKey, { nobitexApiKey = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nobitex API Key (new, recommended)") }, supportingText = { Text("Public key from Nobitex \u2192 API Key settings") })
                OutlinedTextField(nobitexPrivateKey, { nobitexPrivateKey = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Nobitex Private Key (Ed25519, base64)") }, supportingText = { Text("Used only to sign live orders on this device") })
                OutlinedTextField(openAiKey, { openAiKey = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("OpenAI API key") }, supportingText = { Text("Stored locally with Android Keystore") })
                Button(onClick = ::refresh, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WispWhite, contentColor = Color.Black)) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "WORKING…" else "CONNECT & REFRESH")
                }
                OutlinedButton(onClick = { autoRefresh = !autoRefresh }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (autoRefresh) "LIVE REFRESH • ON" else "LIVE REFRESH • OFF")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, null, tint = WispGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(status, color = WispMuted, fontSize = 12.sp)
                }
            }
        }
        snapshot?.let { d ->
            item {
                SectionCard("LIVE SNAPSHOT") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(d.market, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Nobitex public data", color = WispMuted, fontSize = 11.sp) }
                        Column(horizontalAlignment = Alignment.End) { Text(d.lastPrice, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(d.trend.uppercase(Locale.US), color = if (d.trend.contains("bull", true)) WispGreen else if (d.trend.contains("bear", true)) WispRed else WispMuted, fontSize = 10.sp) }
                    }
                    MetricRow("Bid", d.bid, "Ask", d.ask)
                    MetricRow("RSI14", d.rsi14, "EMA20", d.ema20)
                    MetricRow("EMA50", d.ema50, "Volatility", d.volatilityPercent)
                    MetricRow("Spread", d.spreadPercent, "Buy ratio", d.tradeBuyRatio)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (d.trend.contains("bull", true)) Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown, null, tint = if (d.trend.contains("bull", true)) WispGreen else WispRed)
                        Spacer(Modifier.width(8.dp))
                        Text("${d.candleCount} candles • order-book + trade flow", color = WispMuted, fontSize = 11.sp)
                    }
                }
            }
            item {
                SectionCard("AI DECISION ENGINE") {
                    Text("The current Nobitex snapshot is sent to the configured OpenAI API model. The result passes a local Risk Gate before paper execution.", color = WispMuted, fontSize = 12.sp)
                    Button(onClick = ::analyze, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WispPurple, contentColor = WispText)) {
                        Icon(Icons.Outlined.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "ANALYZING…" else "ANALYZE WITH AI")
                    }
                }
            }
        }
        proposal?.let { p ->
            item {
                SectionCard("AI PROPOSAL") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(p.action.uppercase(Locale.US), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("${(p.confidence * 100).toInt()}% confidence", color = WispMuted, fontSize = 12.sp)
                    }
                    Text(p.reason, color = WispMuted, fontSize = 13.sp)
                    MetricRow("Quote amount", "%.2f".format(Locale.US, p.quoteAmount), "Risk", p.status)
                    if (p.status == "pending") {
                        Button(onClick = ::paperExecute, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WispWhite, contentColor = Color.Black)) { Text("APPROVE PAPER EXECUTION") }
                        OutlinedButton(onClick = { proposal = p.copy(status = "denied") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("REJECT") }
                        OutlinedButton(
                            onClick = { showLiveConfirm = !showLiveConfirm },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = WispRed)
                        ) { Text(if (showLiveConfirm) "CANCEL LIVE TRADING" else "LIVE TRADING (REAL MONEY)") }
                        if (showLiveConfirm) {
                            Text("This sends a REAL order to Nobitex using real funds. Type exactly:", color = WispRed, fontSize = 11.sp)
                            Text("CONFIRM LIVE ${p.market}", color = WispText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = liveConfirmText,
                                onValueChange = { liveConfirmText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Confirmation phrase") }
                            )
                            Button(
                                onClick = ::liveExecute,
                                enabled = !busy && liveConfirmText == "CONFIRM LIVE ${p.market}",
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = WispRed, contentColor = Color.Black)
                            ) { Text("SEND REAL ORDER") }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = WispGreen)
                            Spacer(Modifier.width(7.dp))
                            Text("No live order was sent", color = WispMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            SectionCard("ACTIVITY") {
                if (executions.isEmpty()) Text("No paper executions yet.", color = WispMuted)
                else executions.asReversed().take(8).forEach { x -> Text("${x.action.uppercase()}  ${x.market}  •  ${"%.2f".format(Locale.US, x.quoteAmount)}  •  ${x.reference}", fontSize = 12.sp) }
                if (liveExecutions.isNotEmpty()) {
                    Text("LIVE ORDERS", color = WispRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    liveExecutions.asReversed().take(8).forEach { x -> Text("${x.action.uppercase()}  ${x.market}  •  ${"%.2f".format(Locale.US, x.quoteAmount)}  •  ${x.nobitexOrderId}", fontSize = 12.sp, color = WispRed) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, tint = WispMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Standalone • no Termux • no localhost", color = WispMuted, fontSize = 10.sp)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ChatGptWorkspace(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var address by remember { mutableStateOf(CHATGPT_URL) }

    fun openInCustomTab(url: String) {
        val builder = androidx.browser.customtabs.CustomTabsIntent.Builder()
        builder.setShowTitle(true)
        val customTabsIntent = builder.build()
        runCatching { customTabsIntent.launchUrl(context, Uri.parse(url)) }
    }

    fun normalizeInput(input: String): String {
        val value = input.trim()
        if (value.isBlank()) return CHATGPT_URL
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        return if (value.contains(" ") || !value.contains(".")) "https://www.google.com/search?q=$encoded" else "https://$value"
    }

    Column(modifier.fillMaxSize().background(WispBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("CHATGPT WORKSPACE") {
            Text(
                "ChatGPT blocks embedded in-app browsers for security. This opens ChatGPT in a real Chrome tab with your normal signed-in session, then returns you here.",
                color = WispMuted,
                fontSize = 12.sp
            )
            Button(
                onClick = { openInCustomTab(CHATGPT_URL) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = WispWhite, contentColor = Color.Black)
            ) {
                Icon(Icons.Outlined.OpenInBrowser, null)
                Spacer(Modifier.width(8.dp))
                Text("OPEN CHATGPT")
            }
        }
        SectionCard("SEARCH OR OPEN A LINK") {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search or address") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }
            )
            Button(
                onClick = { openInCustomTab(normalizeInput(address)) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = WispPurple, contentColor = WispText)
            ) { Text("OPEN") }
        }
    }
}
@Composable
private fun SurfaceBar(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(WispSurface).padding(8.dp), content = content)
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = WispSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, WispBorder)
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = WispMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            content()
        }
    }
    Spacer(Modifier.height(1.dp))
}

@Composable
private fun MetricRow(a: String, av: String, b: String, bv: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Metric(a, av, Modifier.weight(1f))
        Metric(b, bv, Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(WispSurface2).padding(11.dp)) {
        Text(label.uppercase(Locale.US), color = WispMuted, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
