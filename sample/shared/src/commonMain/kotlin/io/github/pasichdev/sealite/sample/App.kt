package io.github.pasichdev.sealite.sample

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private sealed interface Opened {
    data object Loading : Opened
    class Ready(val services: SampleServices) : Opened
    class Failed(val message: String) : Opened
}

@Composable
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            var opened by remember { mutableStateOf<Opened>(Opened.Loading) }
            LaunchedEffect(Unit) {
                opened = withContext(Dispatchers.Default) {
                    try {
                        Opened.Ready(openSampleServices(appDataDir()))
                    } catch (e: Throwable) {
                        Opened.Failed("${e::class.simpleName}: ${e.message}")
                    }
                }
            }
            DisposableEffect(Unit) {
                onDispose { (opened as? Opened.Ready)?.services?.close() }
            }
            when (val state = opened) {
                Opened.Loading -> Text("Opening the encrypted database...", Modifier.padding(24.dp))
                is Opened.Failed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not open the encrypted database", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, fontFamily = FontFamily.Monospace)
                }
                is Opened.Ready -> Main(state.services)
            }
        }
    }
}

@Composable
private fun Main(services: SampleServices) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = {}, label = { Text("Notes") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("Self-check") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            if (tab == 0) NotesScreen(services) else SelfCheckScreen(services)
        }
    }
}

@Composable
private fun NotesScreen(services: SampleServices) {
    val dao = remember(services) { services.db.noteDao() }
    val notes by dao.observeAll().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Notes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text(services.versionsLine, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(
            "Stored in ${services.dbPath}, encrypted by Sealite (Room + SealiteDriver).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("New note") },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft.trim()
                    draft = ""
                    scope.launch { dao.insert(Note(text = text, createdAtMillis = Clock.System.now().toEpochMilliseconds())) }
                },
            ) { Text("Add") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id }) { note ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(note.text, Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { dao.delete(note.id) } }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelfCheckScreen(services: SampleServices) {
    var results by remember { mutableStateOf<List<CheckItem>?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run() {
        if (running) return
        running = true
        scope.launch {
            results = services.runSelfCheck()
            running = false
        }
    }
    LaunchedEffect(services) { run() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Self-check", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        val current = results
        Text(
            when {
                running -> "Running..."
                current == null -> ""
                current.allPassed() -> "All checks passed"
                else -> "Some checks failed"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Button(enabled = !running, onClick = ::run) { Text("Run again") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(current.orEmpty()) { item ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        (if (item.passed) "✓ " else "✗ ") + item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (item.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    for (line in item.details) {
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp))
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
