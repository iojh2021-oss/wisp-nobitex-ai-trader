package ai.wisp.trader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TraderApp() }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TraderApp() {
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
                status = when (it.status) {
                    "pending" -> "ChatGPT proposal ready • waiting for your approval"
                    else -> "Risk Gate blocked this proposal"
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

    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Wisp Trader") }) }) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AI Trading Control", style = MaterialTheme.typography.headlineSmall)
                        Text("Standalone Android • v0.2.0", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "No Termux, localhost backend, or emulator address is required. The APK talks directly to Nobitex and OpenAI over HTTPS.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("1. Connections", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(
                                value = market,
                                onValueChange = { market = it.uppercase(Locale.US).filter { c -> c.isLetterOrDigit() } },
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
                                label = { Text("Nobitex API token (optional for public market data)") },
                                supportingText = { Text("Encrypted locally with Android Keystore. The Android flow never calls an order endpoint.") }
                            )
                            OutlinedTextField(
                                value = openAiKey,
                                onValueChange = { openAiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text("OpenAI API key") },
                                supportingText = { Text("Your API key is used only for ChatGPT analysis and is encrypted locally.") }
                            )
                            Button(enabled = !busy, onClick = { loadMarket() }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "Working…" else "Connect to Nobitex")
                            }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Connection", style = MaterialTheme.typography.titleMedium)
                            Text(status)
                        }
                    }
                }
                snapshot?.let { data ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("2. Live market data", style = MaterialTheme.typography.titleLarge)
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
                                OutlinedButton(enabled = !busy, onClick = { loadMarket() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Refresh market")
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("3. ChatGPT analysis", style = MaterialTheme.typography.titleLarge)
                                Text("ChatGPT receives only the current market snapshot and returns a structured proposal. It cannot send a Nobitex order.")
                                Button(enabled = !busy, onClick = { analyzeWithChatGpt() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (busy) "Analyzing…" else "Analyze with ChatGPT")
                                }
                            }
                        }
                    }
                }
                proposal?.let { p ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("4. AI Proposal", style = MaterialTheme.typography.titleLarge)
                                Text("${p.action.uppercase(Locale.US)} • ${p.market}")
                                Text("Confidence: ${(p.confidence * 100).toInt()}%")
                                Text("Paper amount: ${"%.2f".format(Locale.US, p.quoteAmount)}")
                                Text("Risk status: ${p.status}")
                                Text(p.reason)
                                if (p.status == "pending") {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(enabled = !busy, onClick = { approve() }, modifier = Modifier.weight(1f)) { Text("Approve paper") }
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
                            if (executions.isEmpty()) {
                                Text("No paper executions yet.")
                            } else {
                                executions.reversed().forEach { x ->
                                    Text("${x.action.uppercase(Locale.US)} ${x.market} • ${"%.2f".format(Locale.US, x.quoteAmount)} • ${x.reference}")
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Safety: standalone and paper-only. Live Nobitex order execution is disabled. Never embed a shared OpenAI or exchange secret in the APK; use your own credentials and rotate them if exposed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
