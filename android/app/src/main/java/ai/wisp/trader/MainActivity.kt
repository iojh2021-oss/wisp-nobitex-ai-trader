package ai.wisp.trader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TraderApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraderApp() {
    var backend by remember { mutableStateOf("http://10.0.2.2:8787") }
    var status by remember { mutableStateOf("Not connected") }
    var proposals by remember { mutableStateOf("No proposals loaded") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Wisp Trader") }) }) { pad ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("AI Trading Control", style = MaterialTheme.typography.headlineSmall) }
                item { Text("Backend: $backend", style = MaterialTheme.typography.bodySmall) }
                item {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                val req = Request.Builder().url("$backend/healthz").build()
                                client.newCall(req).execute().use { it.code.toString() + " " + (it.body?.string() ?: "") }
                            }.getOrElse { "Error: ${it.message}" }
                            status = result
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Checking…" else "Check Backend") }
                }
                item { Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Connection"); Text(status) } } }
                item { Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("AI Proposals"); Text(proposals) } } }
                item {
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            proposals = runCatching {
                                val req = Request.Builder().url("$backend/proposals").build()
                                client.newCall(req).execute().use { it.body?.string() ?: "Empty response" }
                            }.getOrElse { "Error: ${it.message}" }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Refresh Proposals") }
                }
                item { Text("Paper trading is enabled by default. Live order execution is disabled.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
