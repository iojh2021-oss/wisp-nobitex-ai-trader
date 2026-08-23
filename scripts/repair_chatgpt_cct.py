from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
activity = ROOT / "android/app/src/main/java/ai/wisp/trader/MainActivity.kt"
gradle = ROOT / "android/app/build.gradle.kts"

text = activity.read_text(encoding="utf-8")

# Keep the existing Trader UI and all other screens intact. Replace only the
# ChatGPT workspace implementation with a native Android Chrome Custom Tab.
start_marker = '@SuppressLint("SetJavaScriptEnabled")\n@Composable\nprivate fun ChatGptBrowserScreen'
start = text.find(start_marker)
if start < 0:
    raise SystemExit("ChatGptBrowserScreen marker not found")

body_start = text.find("{", start)
if body_start < 0:
    raise SystemExit("ChatGptBrowserScreen body not found")

# Find the matching closing brace while ignoring Kotlin strings and comments.
depth = 0
i = body_start
state = "code"
while i < len(text):
    c = text[i]
    n = text[i + 1] if i + 1 < len(text) else ""
    if state == "code":
        if c == '"':
            state = "string"
        elif c == '/' and n == '/':
            state = "line_comment"
            i += 1
        elif c == '/' and n == '*':
            state = "block_comment"
            i += 1
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    elif state == "string":
        if c == '\\':
            i += 1
        elif c == '"':
            state = "code"
    elif state == "line_comment":
        if c == '\n':
            state = "code"
    elif state == "block_comment":
        if c == '*' and n == '/':
            state = "code"
            i += 1
    i += 1
else:
    raise SystemExit("Could not find end of ChatGptBrowserScreen")

new_function = r'''@Composable
private fun ChatGptBrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(false) }

    fun openChatGpt() {
        val uri = android.net.Uri.parse(CHATGPT_URL)
        val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_OFF)
            .build()

        // Prefer Chrome/Custom Tabs. If no Custom Tabs provider is available,
        // fall back to the device's normal browser instead of showing a
        // permanent Loading state inside the app.
        runCatching {
            customTabs.launchUrl(context, uri)
        }.onFailure {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            context.startActivity(intent)
        }
    }

    androidx.activity.compose.BackHandler(enabled = opened) {
        opened = false
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            scope.launch {
                openChatGpt()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ChatGPT Workspace", style = MaterialTheme.typography.headlineSmall)
        Text(
            "ChatGPT opens through Chrome Custom Tabs. No WebView is used for login or chat, so Google/ChatGPT authentication and cookies are handled by the real browser.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = { opened = true; openChatGpt() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
            Text("  Open ChatGPT")
        }
        OutlinedButton(
            onClick = { opened = true; openChatGpt() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Launch, contentDescription = null)
            Text("  Open workspace again")
        }
    }
}'''

text = text[:start] + new_function + text[end:]

# Remove WebView-only imports and add native browser imports only when needed.
for imp in [
    "import android.annotation.SuppressLint\n",
    "import android.webkit.CookieManager\n",
    "import android.webkit.WebChromeClient\n",
    "import android.webkit.WebSettings\n",
    "import android.webkit.WebView\n",
    "import android.webkit.WebViewClient\n",
    "import androidx.compose.runtime.DisposableEffect\n",
    "import androidx.compose.ui.viewinterop.AndroidView\n",
]:
    text = text.replace(imp, "")

activity.write_text(text, encoding="utf-8")

gradle_text = gradle.read_text(encoding="utf-8")
if 'implementation("androidx.browser:browser:' not in gradle_text:
    needle = 'implementation("androidx.compose.material:material-icons-core")'
    if needle not in gradle_text:
        raise SystemExit("Gradle dependency insertion point not found")
    gradle_text = gradle_text.replace(
        needle,
        needle + '\n    implementation("androidx.browser:browser:1.10.0")',
        1,
    )
    gradle.write_text(gradle_text, encoding="utf-8")

print("ChatGPT workspace repaired: existing Trader UI preserved; WebView replaced by Chrome Custom Tabs.")
