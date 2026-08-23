package ai.wisp.trader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
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

    MaterialTheme(
        colorScheme = WispDarkColors,
        typography = MaterialTheme.typography.copy(fontFamily = FontFamily.SansSerif)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (selectedTab == 0) "Wisp Trader" else "ChatGPT Workspace") }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = WispDarkColors.surface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Outlined.ShowChart, contentDescription = "Trader") },
                        label = { Text("Trader") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "ChatGPT") },
                        label = { Text("ChatGPT") }
                    )
                }
            }
        ) { pad ->
            if (selectedTab == 0) TraderScreen(Modifier.padding(pad))
            else ChatGptBrowserScreen(Modifier.padding(pad))
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

    fun approve() {
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
                Text("Direct HTTPS mode. Termux and localhost are not required.", style = MaterialTheme.typography.bodyMedium)
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
                        supportingText = { Text("Used by the programmatic AI engine; stored with Android Keystore.") }
                    )
                    Button(
                        onClick = ::loadMarket,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) { Text(if (busy) "Working…" else "Connect to Nobitex") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
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
                                Button(onClick = ::approve, modifier = Modifier.weight(1f), enabled = !busy) { Text("Approve paper") }
                                OutlinedButton(onClick = { proposal = p.copy(status = "denied") }, modifier = Modifier.weight(1f), enabled = !busy) { Text("Reject") }
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
                    else executions.reversed().forEach { x ->
                        Text("${x.action.uppercase(Locale.US)} ${x.market} • ${"%.2f".format(Locale.US, x.quoteAmount)} • ${x.reference}")
                    }
                }
            }
        }
        item {
            Text(
                "Safety: this Android app is standalone and paper-only. Do not place shared secrets in source code or APKs.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ChatGptBrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(CHATGPT_URL) }
    var pageTitle by remember { mutableStateOf("ChatGPT") }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    fun normalizeInput(input: String): String {
        val value = input.trim()
        if (value.isBlank()) return CHATGPT_URL
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.contains(" ") || !value.contains(".")) {
            val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
            return "https://www.google.com/search?q=$encoded"
        }
        return "https://$value"
    }

    fun navigate(input: String) {
        errorText = null
        webView?.loadUrl(normalizeInput(input))
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose { webView?.stopLoading() }
    }

    Column(modifier.fillMaxSize()) {
        Surface(color = WispDarkColors.surface) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                        Icon(Icons.Outlined.ArrowForward, contentDescription = "Forward")
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(onGo = { navigate(address) }),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }
                    )
                    IconButton(onClick = { navigate(address) }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Go")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pageTitle, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Outlined.Launch, contentDescription = "Browser help")
                    }
                }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        Box(Modifier.fillMaxSize().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        webView = this
                        setBackgroundColor(AndroidColor.rgb(13, 15, 18))
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = true
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")

                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        cookies.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                return if (url.startsWith("http://") || url.startsWith("https://")) {
                                    false
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                    }
                                    true
                                }
                            }

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
                                pageTitle = view.title?.takeIf { it.isNotBlank() } ?: "ChatGPT"
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                                CookieManager.getInstance().flush()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (request.isForMainFrame) {
                                    loading = false
                                    errorText = error.description?.toString()
                                }
                            }
                        }

                        loadUrl(CHATGPT_URL)
                    }
                },
                update = { current ->
                    webView = current
                }
            )

            errorText?.let { error ->
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp)
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("ChatGPT page could not be loaded", style = MaterialTheme.typography.titleMedium)
                        Text(error)
                        Button(onClick = { webView?.reload() }) { Text("Retry") }
                    }
                }
            }
        }
    }

    if (showHelp) {
        Dialog(onDismissRequest = { showHelp = false }) {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ChatGPT Workspace", style = MaterialTheme.typography.headlineSmall)
                    Text("این تب یک مرورگر داخلی واقعی است: ورود به حساب، کوکی و نشست، JavaScript، جستجو، لینک‌ها، عقب/جلو و Refresh داخل خود Wisp Trader انجام می‌شود.")
                    Text("اگر صفحه ورود به دلیل سیاست امنیتی سرویس داخل WebView اجازه ورود نداد، دکمه زیر همان صفحه را با Chrome باز می‌کند؛ نشست Chrome نیز مستقل از کلید API است.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            showHelp = false
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(CHATGPT_URL)).setPackage("com.android.chrome")
                                )
                            } catch (_: ActivityNotFoundException) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHATGPT_URL)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open in Chrome")
                    }
                    OutlinedButton(onClick = { showHelp = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
