package io.github.maxdac.marknotes.ui.conflicts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.sync.SyncConflict
import io.github.maxdac.marknotes.sync.ConflictChoice
import io.github.maxdac.marknotes.sync.MergeChunk
import io.github.maxdac.marknotes.sync.MergeResult
import io.github.maxdac.marknotes.sync.Side
import io.github.maxdac.marknotes.sync.mergeText
import kotlinx.coroutines.CancellationException

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
                items(state.conflicts, key = { it.copyPath }) { conflict ->
                    val drive = state.drives.firstOrNull { it.account.id == conflict.driveId }?.account
                    val driveName = drive?.let { "${it.provider.displayName} (${it.label})" } ?: "the drive"
                    ConflictCard(
                        conflict = conflict,
                        driveName = driveName,
                        enabled = !state.running,
                        loadMerge = { syncManager.mergePreview(conflict) },
                        onMergeBack = { syncManager.mergeBack(conflict, it) },
                        onDiscardCopy = { syncManager.discardCopy(conflict) },
                        onKeepBoth = { syncManager.keepBoth(conflict) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictCard(
    conflict: SyncConflict,
    driveName: String,
    enabled: Boolean,
    loadMerge: suspend () -> MergeResult,
    onMergeBack: (String) -> Unit,
    onDiscardCopy: () -> Unit,
    onKeepBoth: () -> Unit
) {
    var merge by remember(conflict) { mutableStateOf<MergeResult?>(null) }
    var loadError by remember(conflict) { mutableStateOf<String?>(null) }
    LaunchedEffect(conflict) {
        try {
            merge = loadMerge()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
        }
    }
    val hunks = merge?.chunks.orEmpty().filterIsInstance<MergeChunk.Conflict>()
    val choices = remember(conflict) { mutableStateMapOf<Int, ConflictChoice>() }
    var editing by remember(conflict) { mutableStateOf<String?>(null) }
    val kept = if (conflict.winner == Side.LOCAL) "this device" else driveName
    val copied = if (conflict.winner == Side.LOCAL) driveName else "this device"

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(conflict.path, style = MaterialTheme.typography.titleMedium)
            Text(
                "Changed on this device and on $driveName. The newest version (from $kept) was kept; " +
                    "the version from $copied was saved as ${conflict.copyPath}.",
                style = MaterialTheme.typography.bodySmall
            )
            loadError?.let { Text("Can't merge the copy back: $it", color = MaterialTheme.colorScheme.error) }
            merge?.let { result ->
                val autoMerged = result.chunks.count { it is MergeChunk.Resolved && it.changed }
                Text(
                    if (hunks.isEmpty()) {
                        "The copy can be merged back without conflicts."
                    } else {
                        "${hunks.size} conflicting change(s); $autoMerged other change(s) merge automatically."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            hunks.forEachIndexed { index, hunk ->
                HorizontalDivider()
                Text("Change ${index + 1}", style = MaterialTheme.typography.labelLarge)
                VersionText("Kept version", hunk.local.joinToString(""))
                VersionText("Conflicted copy", hunk.remote.joinToString(""))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HUNK_OPTIONS.forEach { (choice, label) ->
                        FilterChip(
                            selected = choices[index] == choice,
                            onClick = { choices[index] = choice },
                            label = { Text(label) },
                            enabled = enabled
                        )
                    }
                }
            }
            HorizontalDivider()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                merge?.let { result ->
                    Button(
                        onClick = { onMergeBack(mergeText(result.chunks, choices.toMap())) },
                        enabled = enabled && choices.size == hunks.size
                    ) { Text("Merge copy back") }
                    OutlinedButton(onClick = {
                        editing = mergeText(result.chunks, choices.toMap())
                    }, enabled = enabled) {
                        Text("Edit merged")
                    }
                }
                OutlinedButton(onClick = onDiscardCopy, enabled = enabled) { Text("Discard copy") }
                OutlinedButton(onClick = onKeepBoth, enabled = enabled) { Text("Keep both files") }
            }
        }
    }

    editing?.let { initial ->
        EditMergeDialog(
            initial = initial,
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                onMergeBack(text)
            }
        )
    }
}

@Composable
private fun EditMergeDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit merged note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun VersionText(label: String, text: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            text.trimEnd('\n').ifEmpty { "(nothing)" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private val HUNK_OPTIONS = listOf(
    ConflictChoice.LOCAL to "Kept",
    ConflictChoice.REMOTE to "Copy",
    ConflictChoice.BOTH to "Both",
    ConflictChoice.NEITHER to "Neither"
)
