package ai.wisp.trader

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val CHATGPT_HOME = "https://chatgpt.com/"
private const val CHATGPT_PROJECT_CHAT = "https://chatgpt.com/c/6a882026-cca0-83eb-9884-590944f19289"

private val WispColors = darkColorScheme(
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
    outline = Color(0xFF6F727B),
)

class ProMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProWispApp() }
    }
}

@Composable
private fun ProWispApp() {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = WispColors) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(if (tab == 0) "Wisp Trader" else "ChatGPT Workspace") })
            },
            bottomBar = {
                NavigationBar(containerColor = WispColors.surface) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.ShowChart, null) },
                        label = { Text("Trader") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                        label = { Text("ChatGPT") },
                    )
                }
            },
        ) { padding ->
            if (tab == 0) {
                ProTraderDashboard(Modifier.padding(padding))
            } else {
                ChatGPTWorkspace(Modifier.padding(padding))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChatGPTWorkspace(modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(CHATGPT_PROJECT_CHAT) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }

    fun normalize(value: String): String {
        val input = value.trim()
        if (input.isBlank()) return CHATGPT_HOME
        if (input.startsWith("https://") || input.startsWith("http://")) return input
        val query = URLEncoder.encode(input, StandardCharsets.UTF_8.toString())
        return if (input.contains(".") && !input.contains(" ")) "https://$input"
        else "https://www.google.com/search?q=$query"
    }

    fun open(value: String) {
        val url = normalize(value)
        address = url
        webView?.loadUrl(url)
    }

    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy(); webView = null }
    }

    Column(modifier.fillMaxSize()) {
        Surface(color = WispColors.surface) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { webView?.goBack() }, enabled = canBack) { Icon(Icons.Outlined.ArrowBack, null) }
                    IconButton(onClick = { webView?.goForward() }, enabled = canForward) { Icon(Icons.Outlined.ArrowForward, null) }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, null) }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Search or address") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { open(address) }),
                    )
                    IconButton(onClick = { open(address) }) { Icon(Icons.Outlined.Search, null) }
                }
                if (progress in 1..99) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open(CHATGPT_PROJECT_CHAT) }, modifier = Modifier.weight(1f)) { Text("Open project chat") }
                    Button(onClick = { open(CHATGPT_HOME) }, modifier = Modifier.weight(1f)) { Text("ChatGPT home") }
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        loadsImagesAutomatically = true
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        allowFileAccess = false
                        allowContentAccess = false
                        builtInZoomControls = false
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        userAgentString = "$userAgentString WispTrader/0.6"
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            address = url
                            canBack = view.canGoBack()
                            canForward = view.canGoForward()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                            canBack = view.canGoBack()
                            canForward = view.canGoForward()
                        }
                    }
                    webView = this
                    loadUrl(CHATGPT_PROJECT_CHAT)
                }
            },
            update = { view ->
                canBack = view.canGoBack()
                canForward = view.canGoForward()
            },
        )
    }
}
