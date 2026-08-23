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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    background = Color(0xFF080A0D),
    onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF111419),
    onSurface = Color(0xFFF5F7FA),
    surfaceVariant = Color(0xFF171B21),
    onSurfaceVariant = Color(0xFF969DA8),
    outline = Color(0xFF2A3038)
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
                TopAppBar(title = { Text(if (selectedTab == 0) "Wisp Trader" else "ChatGPT Workspace") })
            },
            bottomBar = {
                NavigationBar(containerColor = WispDarkColors.surface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Outlined.ShowChart, "Trader", tint = Color.White) },
                        label = { Text("Trader") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, "ChatGPT", tint = Color.White) },
                        label = { Text("ChatGPT") }
                    )
                }
            }
        ) { padding ->
            if (selectedTab == 0) ProfessionalTraderScreen(Modifier.padding(padding))
            else ChatGptBrowserScreen(Modifier.padding(padding))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChatGptBrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(CHATGPT_URL) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun normalize(input: String): String {
        val value = input.trim()
        if (value.isBlank()) return CHATGPT_URL
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val q = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        return if (value.contains(" ") || !value.contains(".")) "https://www.google.com/search?q=$q" else "https://$value"
    }

    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    DisposableEffect(Unit) {
        onDispose { webView?.stopLoading(); webView = null }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = Color.White) }
            IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) { Icon(Icons.Outlined.ArrowForward, "Forward", tint = Color.White) }
            IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, "Reload", tint = Color.White) }
            IconButton(onClick = { openExternalBrowser(context, webView?.url ?: address) }) { Icon(Icons.Outlined.Launch, "Browser", tint = Color.White) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Address / search") }
            )
            IconButton(onClick = { webView?.loadUrl(normalize(address)) }) { Icon(Icons.Outlined.Search, "Search", tint = Color.White) }
        }
        if (loading) Text("Loading…", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = Color.White)

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                WebView(ctx).apply {
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
                        setAcceptThirdPartyCookies(this@apply, true)
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            loading = true; address = url; canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false; address = url; canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                        }
                        override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) loading = false
                        }
                    }
                    loadUrl(CHATGPT_URL)
                }.also { webView = it }
            },
            update = { view -> canGoBack = view.canGoBack(); canGoForward = view.canGoForward() }
        )
    }
}

private fun openExternalBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}
