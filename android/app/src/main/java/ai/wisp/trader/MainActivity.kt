package ai.wisp.trader

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val CHATGPT_URL = "https://chatgpt.com/c/6a882026-cca0-83eb-9884-590944f19289"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TraderApp() }
    }
}

@Composable
private fun TraderApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (selectedTab == 0) "Wisp Trader" else "ChatGPT Workspace") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Text("📈") }, label = { Text("Trader") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Text("💬") }, label = { Text("ChatGPT") })
            }
        }
    ) { pad ->
        if (selectedTab == 0) TraderScreen(Modifier.padding(pad)) else ChatGptScreen(Modifier.padding(pad))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TraderScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secrets = remember { SecureTokenStore(context.applicationContext) }
    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    val engine = remember { LocalTradingEngine(httpClient) }
    val scope = rememberCoroutineScope()

    var market by remember { mutableStateOf("BTCIRT") }
    var openAiKey by remember { mutableStateOf(secrets.readOpenAiKey()) }
    var nobitexToken by remember { mutableStateOf(secrets.readNobitexToken()) }
    var snapshot by remember { mutableStateOf<LocalTradingEngine.MarketSnapshot?>(null) }
    var proposal by remember { mutableStateOf<LocalTradingEngine.Proposal?>(null) }
    var executions by remember { mutableStateOf(emptyList<LocalTradingEngine.PaperExecution>()) }
    var status by remember { mutableStateOf("Ready — standalone mode; Termux is not required") }
    var busy by remember { mutableStateOf(false) }

    fun saveSecrets() {
        secrets.saveOpenAiKey(openAiKey)
        secrets.saveNobitexToken(nobitexToken)
    }

    fun loadMarket() {
        busy = true
        saveSecrets()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { engine.fetchMarket(market, nobitexToken) } }
            result.onSuccess {
                snapshot = it
                status = "Nobitex connected • ${it.market} • read-only market data"
            }.onFailure { status = "Market data error: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }

    fun analyzeWithChatGpt() {
        val current = snapshot
        if (current == null) { status = "Fetch market data first"; return }
        if (openAiKey.isBlank()) { status = "Add an OpenAI API key first"; return }
        busy = true
        saveSecrets()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { engine.analyze(current, openAiKey) } }
            result.onSuccess {
                proposal = it
                status = if (it.status == "pending") "ChatGPT proposal ready • waiting for your approval" else "Risk Gate blocked this proposal"
            }.onFailure { status = "AI analysis error: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }

    fun approve() {
        val current = proposal ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { engine.approvePaper(current) } }
            result.onSuccess {
                executions = executions + it
                proposal = current.copy(status = "approved_paper")
                status = "Paper execution completed • no real order was sent"
            }.onFailure { status = "Approval blocked: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }

    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
        LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AI Trading Control", style = MaterialTheme.typography.headlineSmall)
                    Text("Standalone Android • v0.3.0", style = MaterialTheme.typography.labelLarge)
                    Text("Direct HTTPS mode. Termux and localhost are not required.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Connections", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(value = market, onValueChange = { market = it.uppercase(Locale.US).filter(Char::isLetterOrDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nobitex market") }, supportingText = { Text("Example: BTCIRT or BTCUSDT") })
                        OutlinedTextField(value = nobitexToken, onValueChange = { nobitexToken = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Nobitex API token (optional for public data)") })
                        OutlinedTextField(value = openAiKey, onValueChange = { openAiKey = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("OpenAI API key") }, supportingText = { Text("Used by the programmatic AI engine; stored with Android Keystore.") })
                        Button(enabled = !busy, onClick = ::loadMarket, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Working…" else "Connect to Nobitex") }
                    }
                }
            }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Status", style = MaterialTheme.typography.titleMedium); Text(status) } } }
            snapshot?.let { data ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Live market data", style = MaterialTheme.typography.titleLarge)
                            Text("${data.market} • read only")
                            Text("Last: ${data.lastPrice}")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("Bid: ${data.bid}", Modifier.weight(1f)); Text("Ask: ${data.ask}", Modifier.weight(1f)) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("High: ${data.high}", Modifier.weight(1f)); Text("Low: ${data.low}", Modifier.weight(1f)) }
                            Text("Volume: ${data.volume}")
                            OutlinedButton(enabled = !busy, onClick = ::loadMarket, modifier = Modifier.fillMaxWidth()) { Text("Refresh market") }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ChatGPT analysis", style = MaterialTheme.typography.titleLarge)
                            Text("Structured AI analysis → Risk Gate → paper proposal. Live order execution remains disabled.")
                            Button(enabled = !busy, onClick = ::analyzeWithChatGpt, modifier = Modifier.fillMaxWidth()) { Text("Analyze with ChatGPT API") }
                        }
                    }
                }
            }
            proposal?.let { p ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AI Proposal", style = MaterialTheme.typography.titleLarge)
                            Text("${p.action.uppercase(Locale.US)} • ${p.market}")
                            Text("Confidence: ${(p.confidence * 100).toInt()}%")
                            Text("Paper amount: ${"%.2f".format(Locale.US, p.quoteAmount)}")
                            Text("Risk status: ${p.status}")
                            Text(p.reason)
                            if (p.status == "pending") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !busy, onClick = ::approve, modifier = Modifier.weight(1f)) { Text("Approve paper") }
                                    OutlinedButton(enabled = !busy, onClick = { proposal = p.copy(status = "denied") }, modifier = Modifier.weight(1f)) { Text("Reject") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paper executions", style = MaterialTheme.typography.titleLarge)
                        if (executions.isEmpty()) Text("No paper executions yet.")
                        else executions.reversed().forEach { x -> Text("${x.action.uppercase(Locale.US)} ${x.market} • ${"%.2f".format(Locale.US, x.quoteAmount)} • ${x.reference}") }
                    }
                }
            }
            item { Text("Safety: this Android app is standalone and paper-only. Do not place shared secrets in source code or APKs.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChatGptScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = settings.userAgentString + " WispTrader/0.3.0"
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(CHATGPT_URL)
        }
    }

    DisposableEffect(webView) {
        onDispose { webView.stopLoading(); webView.destroy() }
    }

    Column(modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ChatGPT account workspace", style = MaterialTheme.typography.titleMedium)
                Text("This tab displays ChatGPT inside the app and keeps its web session/cookies in the Android WebView. Sign in with your own account if requested.", style = MaterialTheme.typography.bodySmall)
                Text("Important: this is the ChatGPT website UI, not an OpenAI API connection. The Trader tab uses the API separately when an API key is configured.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { webView.goBack() }) { Text("Back") }
                    OutlinedButton(onClick = { webView.reload() }) { Text("Reload") }
                }
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().weight(1f))
    }
}
