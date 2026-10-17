package io.github.maxdac.marknotes.ui.conflicts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.sync.ConflictResolution
import io.github.maxdac.marknotes.core.sync.SyncConflict

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(onBack: () -> Unit) {
    val syncManager = LocalContext.current.appContainer.syncManager
    val state by syncManager.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync conflicts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (state.conflicts.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conflicts")
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.conflicts, key = { it.path }) { conflict ->
                    ConflictCard(conflict, enabled = !state.running) { resolution ->
                        syncManager.resolve(conflict, resolution)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(conflict: SyncConflict, enabled: Boolean, onResolve: (ConflictResolution) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(conflict.path, style = MaterialTheme.typography.titleMedium)
            Text("Changed on this device and in Proton Drive.", style = MaterialTheme.typography.bodySmall)
            VersionText("This device", conflict.localText)
            VersionText("Proton Drive", conflict.remoteText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResolve(ConflictResolution.KEEP_LOCAL) }, enabled = enabled) { Text("Keep local") }
                OutlinedButton(onClick = { onResolve(ConflictResolution.KEEP_REMOTE) }, enabled = enabled) {
                    Text("Keep remote")
                }
            }
            OutlinedButton(onClick = { onResolve(ConflictResolution.KEEP_BOTH) }, enabled = enabled) {
                Text("Keep both")
            }
        }
    }
}

@Composable
private fun VersionText(label: String, text: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(text.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
