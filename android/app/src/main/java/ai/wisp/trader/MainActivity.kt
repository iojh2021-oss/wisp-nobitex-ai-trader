package ai.wisp.trader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val CHATGPT_URL = "https://chatgpt.com/c/6a882026-cca0-83eb-9884-590944f19289"

private val WispDarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFE9E9EE),
    onPrimaryContainer = Color(0xFF15161A),
    secondary = Color(0xFFD8C9FF),
    onSecondary = Color(0xFF241C36),
    secondaryContainer = Color(0xFF342C46),
    onSecondaryContainer = Color(0xFFEDE6FF),
    background = Color(0xFF0D0F12),
    onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF15171B),
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF202329),
    onSurfaceVariant = Color(0xFFBFC1C9),
    outline = Color(0xFF6F727B)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TraderApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraderApp() {
    var selectedTab by remember { mutableIntStateOf(0) }

    MaterialTheme(colorScheme = WispDarkColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (selectedTab == 0) "Wisp Trader" else "ChatGPT Workspace")
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = WispDarkColors.surface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Outlined.ShowChart, "Trader") },
                        label = { Text("Trader") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, "ChatGPT") },
                        label = { Text("ChatGPT") }
                    )
                }
            }
        ) { padding ->
            if (selectedTab == 0) {
                TraderScreen(Modifier.padding(padding))
            } else {
                ChatGptBrowserScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun TraderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.fetchMarket(market, nobitexToken) }
            }
            result.onSuccess {
                snapshot = it
                status = "Nobitex connected • ${it.market} • read-only market data"
            }.onFailure {
                status = "Market data error: ${it.message ?: it.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun analyzeWithChatGpt() {
        val current = snapshot
        if (current == null) {
            status = "Fetch market data first"
            return
        }
        if (openAiKey.isBlank()) {
            status = "Add an OpenAI API key first"
            return
        }
        busy = true
        saveSecrets()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.analyze(current, openAiKey) }
            }
            result.onSuccess {
                proposal = it
                status = if (it.status == "pending") {
                    "ChatGPT proposal ready • waiting for your approval"
                } else {
                    "Risk Gate blocked this proposal"
                }
            }.onFailure {
                status = "AI analysis error: ${it.message ?: it.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun approvePaper() {
        val current = proposal ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { engine.approvePaper(current) }
            }
            result.onSuccess {
                executions = executions + it
                proposal = current.copy(status = "approved_paper")
                status = "Paper execution completed • no real order was sent"
            }.onFailure {
                status = "Approval blocked: ${it.message ?: it.javaClass.simpleName}"
            }
            busy = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AI Trading Control", style = MaterialTheme.typography.headlineSmall)
                Text("Standalone Android • v0.5.0", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Direct HTTPS mode. Termux and localhost are not required.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connections", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = market,
                        onValueChange = { market = it.uppercase(Locale.US).filter(Char::isLetterOrDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Nobitex market") },
                        supportingText = { Text("Example: BTCIRT or BTCUSDT") }
                    )
                    OutlinedTextField(
                        value = nobitexToken,
                        onValueChange = { nobitexToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Nobitex API token (optional for public data)") }
                    )
                    OutlinedTextField(
                        value = openAiKey,
                        onValueChange = { openAiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("OpenAI API key") },
                        supportingText = {
                            Text("Used by the programmatic AI engine; stored with Android Keystore.")
                        }
                    )
                    Button(
                        onClick = ::loadMarket,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) {
                        Text(if (busy) "Working…" else "Connect to Nobitex")
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text(status)
                }
            }
        }

        snapshot?.let { data ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Live market data", style = MaterialTheme.typography.titleLarge)
                        Text("${data.market} • read only")
                        Text("Last: ${data.lastPrice}")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Bid: ${data.bid}", Modifier.weight(1f))
                            Text("Ask: ${data.ask}", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("High: ${data.high}", Modifier.weight(1f))
                            Text("Low: ${data.low}", Modifier.weight(1f))
                        }
                        Text("Volume: ${data.volume}")
                        OutlinedButton(
                            onClick = ::loadMarket,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        ) { Text("Refresh market") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ChatGPT analysis", style = MaterialTheme.typography.titleLarge)
                        Text("Structured AI analysis → Risk Gate → paper proposal. Live order execution remains disabled.")
                        Button(
                            onClick = ::analyzeWithChatGpt,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        ) { Text("Analyze with ChatGPT API") }
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
                                Button(
                                    onClick = ::approvePaper,
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
                                ) { Text("Approve paper") }
                                OutlinedButton(
                                    onClick = { proposal = p.copy(status = "denied") },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
                                ) { Text("Reject") }
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
                    if (executions.isEmpty()) {
                        Text("No paper executions yet.")
                    } else {
                        executions.reversed().forEach { execution ->
                            Text(
                                "${execution.action.uppercase(Locale.US)} ${execution.market} • " +
                                    "${"%.2f".format(Locale.US, execution.quoteAmount)} • ${execution.reference}"
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Safety: standalone and paper-only. Do not place shared secrets in source code or APKs.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChatGptBrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(CHATGPT_URL) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    fun normalizeInput(input: String): String {
        val value = input.trim()
        if (value.isBlank()) return CHATGPT_URL
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        return if (value.contains(" ") || !value.contains(".")) {
            "https://www.google.com/search?q=$encoded"
        } else {
            "https://$value"
        }
    }

    fun navigate(input: String) {
        errorText = null
        val url = normalizeInput(input)
        address = url
        webView?.loadUrl(url)
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                webView = null
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Surface(color = WispDarkColors.surface) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                        Icon(Icons.Outlined.ArrowBack, "Back")
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                        Icon(Icons.Outlined.ArrowForward, "Forward")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Outlined.Refresh, "Reload")
                    }
                    IconButton(onClick = { webView?.let { openExternalBrowser(context, it.url ?: address) } }) {
                        Icon(Icons.Outlined.Launch, "Open in browser")
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Address / search") }
                    )
                    IconButton(onClick = { navigate(address) }) {
                        Icon(Icons.Outlined.Search, "Search")
                    }
                }
            }
        }

        if (loading) {
            Text("Loading ChatGPT…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        errorText?.let { message ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Web connection error", style = MaterialTheme.typography.titleMedium)
                    Text(message)
                    OutlinedButton(onClick = { webView?.reload() }) { Text("Retry") }
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                WebView(ctx).apply {
                    configureChatGptWebView()
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            loading = true
                            errorText = null
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                loading = false
                                errorText = error.description?.toString() ?: "Unable to load page"
                            }
                        }
                    }
                    webChromeClient = WebChromeClient()
                    loadUrl(CHATGPT_URL)
                }.also { created -> webView = created }
            },
            update = { view ->
                canGoBack = view.canGoBack()
                canGoForward = view.canGoForward()
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureChatGptWebView() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureChatGptWebView, true)
    }
    isFocusable = true
    isFocusableInTouchMode = true
    requestFocus()
}

private fun openExternalBrowser(context: Context, url: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        context.startActivity(intent)
    }
}
