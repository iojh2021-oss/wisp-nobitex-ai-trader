package ai.wisp.trader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TraderApp() }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TraderApp() {
    // 127.0.0.1 is the correct default when the Wisp backend runs on the same phone.
    // 10.0.2.2 is reserved for the Android emulator and is not correct on a physical phone.
    var backend by remember { mutableStateOf("http://127.0.0.1:8787") }
    var status by remember { mutableStateOf("Not connected") }
    var proposals by remember { mutableStateOf("No proposals loaded") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    fun normalizedBaseUrl(): String = backend.trim().trimEnd('/')

    fun checkBackend() {
        val base = normalizedBaseUrl()
        if (base.isBlank()) {
            status = "Error: backend URL is empty"
            return
        }
        busy = true
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("$base/healthz").get().build()
                    client.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            "Connected • HTTP ${response.code}"
                        } else {
                            "Backend returned HTTP ${response.code}"
                        }
                    }
                }.getOrElse { error ->
                    "Error: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            busy = false
        }
    }

    fun refreshProposals() {
        val base = normalizedBaseUrl()
        if (base.isBlank()) {
            proposals = "Error: backend URL is empty"
            return
        }
        scope.launch {
            proposals = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("$base/proposals").get().build()
                    client.newCall(req).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            "Backend returned HTTP ${response.code}: $body"
                        } else {
                            body.ifBlank { "No proposals available" }
                        }
                    }
                }.getOrElse { error ->
                    "Error: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Wisp Trader") }) }) { pad ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("AI Trading Control", style = MaterialTheme.typography.headlineSmall)
                }
                item {
                    OutlinedTextField(
                        value = backend,
                        onValueChange = { backend = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Backend URL") },
                        supportingText = { Text("Same phone: http://127.0.0.1:8787 • Emulator: http://10.0.2.2:8787") }
                    )
                }
                item {
                    Button(
                        enabled = !busy,
                        onClick = { checkBackend() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "Checking…" else "Check Backend")
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Connection", style = MaterialTheme.typography.titleMedium)
                            Text(status)
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("AI Proposals", style = MaterialTheme.typography.titleMedium)
                            Text(proposals)
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { refreshProposals() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Refresh Proposals")
                    }
                }
                item {
                    Text(
                        "Paper trading is enabled by default. Live order execution is disabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
