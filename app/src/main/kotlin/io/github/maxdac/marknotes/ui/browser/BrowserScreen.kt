package io.github.maxdac.marknotes.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.NoteEntry
import io.github.maxdac.marknotes.core.notes.NotePaths

const val NEW_NOTE_FAB_TAG = "new-note-fab"

private sealed interface BrowserDialog {
    data object NewNote : BrowserDialog
    data object NewFolder : BrowserDialog
    data class Rename(val entry: NoteEntry) : BrowserDialog
    data class Delete(val entry: NoteEntry) : BrowserDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    folder: String,
    onOpenFolder: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConflicts: () -> Unit,
    onBack: () -> Unit
) {
    val container = LocalContext.current.appContainer
    val vm: BrowserViewModel = viewModel { BrowserViewModel(container, folder) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (folder.isEmpty()) "MarkNotes" else NotePaths.name(folder)) },
                navigationIcon = {
                    if (folder.isNotEmpty()) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { dialog = BrowserDialog.NewFolder }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                    }
                    IconButton(onClick = vm::sync, enabled = !sync.running) {
                        Icon(Icons.Filled.Sync, contentDescription = "Sync now")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = BrowserDialog.NewNote },
                modifier = Modifier.testTag(NEW_NOTE_FAB_TAG),
                icon = { Icon(Icons.Filled.Edit, contentDescription = "New note") },
                text = { Text("New note") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (sync.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (sync.conflicts.isNotEmpty()) {
                Card(
                    onClick = onOpenConflicts,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Text(
                            "${sync.conflicts.size} sync conflict(s). Tap to resolve.",
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notes here yet", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            onClick = { if (entry.isFolder) onOpenFolder(entry.path) else onOpenNote(entry.path) },
                            onRename = { dialog = BrowserDialog.Rename(entry) },
                            onDelete = { dialog = BrowserDialog.Delete(entry) }
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        BrowserDialog.NewNote -> NameDialog("New note", "", onDismiss = { dialog = null }) { name ->
            dialog = null
            vm.createNote(name, onOpenNote)
        }

        BrowserDialog.NewFolder -> NameDialog("New folder", "", onDismiss = { dialog = null }) { name ->
            dialog = null
            vm.createFolder(name)
        }

        is BrowserDialog.Rename -> NameDialog("Rename", d.entry.displayName, onDismiss = { dialog = null }) { name ->
            dialog = null
            vm.rename(d.entry, name)
        }

        is BrowserDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.entry.displayName}?") },
            text = {
                Text(
                    if (d.entry.isFolder) {
                        "The folder and all its notes will be deleted."
                    } else {
                        "The note will be deleted."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    vm.delete(d.entry)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )

        null -> Unit
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }
        )
    }
}

@Composable
private fun EntryRow(entry: NoteEntry, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (entry.isFolder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.Notes,
                contentDescription = null
            )
        },
        headlineContent = { Text(entry.displayName) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menu = false
                        onRename()
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = {
                    name = it
                }, singleLine = true, label = { Text("Name") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
