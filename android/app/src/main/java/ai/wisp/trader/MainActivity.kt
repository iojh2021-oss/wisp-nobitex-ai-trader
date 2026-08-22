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
import androidx.compose.foundation.lazy.items
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
import okhttp3.Request
import org.json.JSONArray

private const val DEFAULT_BACKEND = "https://wisp-nobitex-ai-trader-api.onrender.com"

data class ProposalUi(
    val id: String,
    val market: String,
    val action: String,
    val amount: String,
    val confidence: String,
    val reason: String,
    val status: String,
)

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
    val tokenStore = remember { SecureTokenStore(context.applicationContext) }
    var backend by remember { mutableStateOf(DEFAULT_BACKEND) }
    var token by remember { mutableStateOf(tokenStore.read()) }
    var status by remember { mutableStateOf("Not connected") }
    var proposals by remember { mutableStateOf(emptyList<ProposalUi>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    fun baseUrl(): String = backend.trim().trimEnd('/')

    fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer ${token.trim()}")
        return builder
    }

    fun checkBackend() {
        val base = baseUrl()
        if (!base.startsWith("https://")) {
            status = "Error: use an HTTPS cloud backend"
            return
        }
        busy = true
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(requestBuilder("$base/healthz").get().build()).execute().use { response ->
                        if (response.isSuccessful) "Connected • HTTP ${response.code}"
                        else "Backend returned HTTP ${response.code}"
                    }
                }.getOrElse { error -> "Error: ${error.message ?: error.javaClass.simpleName}" }
            }
            busy = false
        }
    }

    fun refreshProposals() {
        val base = baseUrl()
        if (!base.startsWith("https://")) {
            status = "Error: use an HTTPS cloud backend"
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(requestBuilder("$base/proposals").get().build()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) error("HTTP ${response.code}: $body")
                        val array = JSONArray(body)
                        buildList {
                            for (i in 0 until array.length()) {
                                val p = array.getJSONObject(i)
                                add(
                                    ProposalUi(
                                        id = p.optString("id"),
                                        market = p.optString("market", "—"),
                                        action = p.optString("action", "hold").uppercase(),
                                        amount = "%.2f".format(p.optDouble("quote_amount", 0.0)),
                                        confidence = "%.0f%%".format(p.optDouble("confidence", 0.0) * 100),
                                        reason = p.optString("reason", "No reason provided"),
                                        status = p.optString("status", "unknown"),
                                    )
                                )
                            }
                        }
                    }
                }
            }
            result.onSuccess { proposals = it; status = "Proposals refreshed • ${it.size} item(s)" }
                .onFailure { status = "Proposals error: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }

    fun decide(proposal: ProposalUi, approve: Boolean) {
        busy = true
        scope.launch {
            val path = if (approve) "approve" else "deny"
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "${baseUrl()}/$path?id=${java.net.URLEncoder.encode(proposal.id, "UTF-8")}"
                    client.newCall(requestBuilder(url).post(okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) error("HTTP ${response.code}: $body")
                        "${if (approve) "Approved" else "Denied"} • ${proposal.market}"
                    }
                }.getOrElse { "Action error: ${it.message ?: it.javaClass.simpleName}" }
            }
            refreshProposals()
        }
    }

    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Wisp Trader") }) }) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text("AI Trading Control", style = MaterialTheme.typography.headlineSmall) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Cloud connection", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = backend,
                                onValueChange = { backend = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Backend HTTPS URL") },
                                supportingText = { Text("No Termux is required. Use the cloud backend created from render.yaml.") }
                            )
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text("Backend access token") },
                                supportingText = { Text("Stored encrypted with Android Keystore on this device.") }
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy, onClick = { tokenStore.save(token); checkBackend() }, modifier = Modifier.weight(1f)) {
                                    Text(if (busy) "Checking…" else "Connect")
                                }
                                OutlinedButton(onClick = { token = ""; tokenStore.clear() }, modifier = Modifier.weight(1f)) {
                                    Text("Clear token")
                                }
                            }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Connection", style = MaterialTheme.typography.titleMedium)
                            Text(status)
                        }
                    }
                }
                item {
                    OutlinedButton(enabled = !busy, onClick = { refreshProposals() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Refresh AI proposals")
                    }
                }
                if (proposals.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text("No proposals yet. The cloud Wisp runtime creates proposals only when AI confidence and deterministic risk limits pass.", Modifier.padding(16.dp))
                        }
                    }
                }
                items(proposals, key = { it.id }) { proposal ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${proposal.action} • ${proposal.market}", style = MaterialTheme.typography.titleLarge)
                            Text("Amount: ${proposal.amount} • Confidence: ${proposal.confidence}")
                            Text("Status: ${proposal.status}")
                            Text(proposal.reason, style = MaterialTheme.typography.bodyMedium)
                            if (proposal.status == "pending") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !busy, onClick = { decide(proposal, true) }, modifier = Modifier.weight(1f)) { Text("Approve") }
                                    OutlinedButton(enabled = !busy, onClick = { decide(proposal, false) }, modifier = Modifier.weight(1f)) { Text("Deny") }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Safety: paper trading is enabled. Live financial execution remains disabled in this build.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
