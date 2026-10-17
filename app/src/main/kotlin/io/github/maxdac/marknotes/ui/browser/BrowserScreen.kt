package io.github.maxdac.marknotes.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.NoteEntry
import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.sync.SyncState
import kotlinx.coroutines.launch

const val NEW_NOTE_FAB_TAG = "new-note-fab"

private sealed interface BrowserDialog {
    data object NewNote : BrowserDialog
    data object NewFolder : BrowserDialog
    data class Rename(val entry: NoteEntry) : BrowserDialog
    data class Move(val entry: NoteEntry) : BrowserDialog
    data class Delete(val entry: NoteEntry) : BrowserDialog
}

/** Orders folder paths as a depth-first tree (a, a/b, a b) rather than plain string order. */
private val treeOrder = Comparator<String> { a, b ->
    val left = a.split('/')
    val right = b.split('/')
    left.zip(right).map { (x, y) -> x.compareTo(y, ignoreCase = true) }.firstOrNull { it != 0 }
        ?: left.size.compareTo(right.size)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    folder: String,
    onOpenFolder: (String) -> Unit,
    onJumpToFolder: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConflicts: () -> Unit,
    onBack: () -> Unit
) {
    val container = LocalContext.current.appContainer
    val vm: BrowserViewModel = viewModel { BrowserViewModel(container, folder) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sortedFolders = remember(folders) { folders.sortedWith(treeOrder) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            FolderDrawer(sortedFolders, current = folder) { target ->
                scope.launch { drawerState.close() }
                if (target != folder) onJumpToFolder(target)
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (folder.isEmpty()) "MarkNotes" else NotePaths.name(folder)) },
                    navigationIcon = {
                        if (folder.isNotEmpty()) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Folders")
                            }
                        }
                    },
                    actions = {
                        if (folder.isNotEmpty()) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.AccountTree, contentDescription = "Folders")
                            }
                        }
                        IconButton(onClick = { dialog = BrowserDialog.NewFolder }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                        }
                        IconButton(onClick = vm::sync, enabled = !sync.running && sync.hasDrives) {
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
            BrowserContent(
                padding = padding,
                entries = entries,
                sync = sync,
                onOpenConflicts = onOpenConflicts,
                onOpen = { entry -> if (entry.isFolder) onOpenFolder(entry.path) else onOpenNote(entry.path) },
                onDialog = { dialog = it }
            )
        }
    }

    BrowserDialogs(
        dialog = dialog,
        folders = sortedFolders,
        vm = vm,
        onOpenNote = onOpenNote,
        onDismiss = { dialog = null }
    )

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
private fun BrowserContent(
    padding: PaddingValues,
    entries: List<NoteEntry>,
    sync: SyncState,
    onOpenConflicts: () -> Unit,
    onOpen: (NoteEntry) -> Unit,
    onDialog: (BrowserDialog) -> Unit
) {
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
                        onClick = { onOpen(entry) },
                        onRename = { onDialog(BrowserDialog.Rename(entry)) },
                        onMove = { onDialog(BrowserDialog.Move(entry)) },
                        onDelete = { onDialog(BrowserDialog.Delete(entry)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserDialogs(
    dialog: BrowserDialog?,
    folders: List<String>,
    vm: BrowserViewModel,
    onOpenNote: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        BrowserDialog.NewNote -> NameDialog("New note", "", onDismiss = onDismiss) { name ->
            onDismiss()
            vm.createNote(name, onOpenNote)
        }

        BrowserDialog.NewFolder -> NameDialog("New folder", "", onDismiss = onDismiss) { name ->
            onDismiss()
            vm.createFolder(name)
        }

        is BrowserDialog.Rename -> NameDialog("Rename", dialog.entry.displayName, onDismiss = onDismiss) { name ->
            onDismiss()
            vm.rename(dialog.entry, name)
        }

        is BrowserDialog.Move -> MoveDialog(dialog.entry, folders, onDismiss) { target ->
            onDismiss()
            vm.move(dialog.entry, target)
        }

        is BrowserDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete ${dialog.entry.displayName}?") },
            text = {
                Text(
                    if (dialog.entry.isFolder) {
                        "The folder and all its notes will be deleted."
                    } else {
                        "The note will be deleted."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    vm.delete(dialog.entry)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )

        null -> Unit
    }
}

/** Folder tree; folders are collapsed except the ancestors of [current]. */
@Composable
private fun FolderDrawer(folders: List<String>, current: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable(current) {
        mutableStateOf(generateSequence(current.ifEmpty { null }) { NotePaths.parent(it).ifEmpty { null } }.toSet())
    }
    val hasChildren = remember(folders) { folders.map(NotePaths::parent).toSet() }
    val visible = folders.filter { path ->
        generateSequence(NotePaths.parent(path)) { if (it.isEmpty()) null else NotePaths.parent(it) }
            .all { it.isEmpty() || it in expanded }
    }
    ModalDrawerSheet {
        Text("Folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LazyColumn {
            item {
                NavigationDrawerItem(
                    label = { Text("All notes") },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    selected = current.isEmpty(),
                    onClick = { onSelect("") },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            items(visible, key = { it }) { path ->
                val open = path in expanded
                NavigationDrawerItem(
                    label = { Text(NotePaths.name(path)) },
                    icon = {
                        Icon(if (open) Icons.Filled.FolderOpen else Icons.Filled.Folder, contentDescription = null)
                    },
                    badge = {
                        if (path in hasChildren) {
                            IconButton(onClick = { expanded = if (open) expanded - path else expanded + path }) {
                                Icon(
                                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (open) "Collapse" else "Expand"
                                )
                            }
                        }
                    },
                    selected = path == current,
                    onClick = { onSelect(path) },
                    modifier = Modifier.padding(start = 12.dp + 16.dp * NotePaths.depth(path), end = 12.dp)
                )
            }
        }
    }
}

/** Picks the folder to move [entry] into. The entry's own folder and its subtree are not offered. */
@Composable
private fun MoveDialog(entry: NoteEntry, folders: List<String>, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    val parent = NotePaths.parent(entry.path)
    val targets =
        listOf("") + folders.filterNot { entry.isFolder && (it == entry.path || NotePaths.isAncestor(entry.path, it)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${entry.displayName} to") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(targets, key = { it }) { target ->
                    val enabled = target != parent
                    ListItem(
                        modifier = Modifier
                            .clickable(enabled = enabled) { onMove(target) }
                            .padding(start = 16.dp * NotePaths.depth(target)),
                        leadingContent = {
                            Icon(
                                if (target.isEmpty()) Icons.Filled.Home else Icons.Filled.Folder,
                                contentDescription = null
                            )
                        },
                        headlineContent = {
                            Text(
                                if (target.isEmpty()) "MarkNotes" else NotePaths.name(target),
                                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EntryRow(
    entry: NoteEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
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
                    DropdownMenuItem(text = { Text("Move to…") }, onClick = {
                        menu = false
                        onMove()
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
