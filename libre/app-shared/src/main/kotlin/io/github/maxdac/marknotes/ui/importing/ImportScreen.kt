package io.github.maxdac.marknotes.ui.importing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.ui.editor.MarkdownPreview
import java.io.File
import java.util.UUID

/**
 * Turns content shared from another app into a new note: shows a preview, lets the user pick the title and
 * folder and choose between auto-formatting as Markdown and keeping the text as it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(content: SharedContent, onSaved: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val previewId = rememberSaveable { UUID.randomUUID().toString() }
    val vm: ImportViewModel = viewModel {
        val container = context.appContainer
        ImportViewModel(
            container = container,
            reader = SharedContentReader(context.applicationContext, container.imageImporter),
            imageImporter = container.imageImporter,
            content = content,
            previewRoot = File(context.cacheDir, "import-preview/$previewId")
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)
    val snackbar = remember { SnackbarHostState() }

    BackHandler(onBack = onCancel)
    LaunchedEffect(state.savedPath) { state.savedPath?.let { currentOnSaved(it) } }
    LaunchedEffect(state.saveError) {
        state.saveError?.let {
            snackbar.showSnackbar(it)
            vm.dismissSaveError()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Import note") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(
                        onClick = vm::save,
                        enabled = state.error == null && !state.loading && !state.saving,
                        modifier = Modifier.testTag(SAVE_TAG)
                    ) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            val error = state.error
            when {
                error != null -> ImportError(error, onCancel)

                state.loading && state.body.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> ImportForm(state, vm, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImportError(message: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(ERROR_TAG))
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun ImportForm(state: ImportUiState, vm: ImportViewModel, modifier: Modifier) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = state.title,
            onValueChange = vm::setTitle,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TITLE_TAG)
        )
        FolderPicker(state.folder, state.folders, vm::setFolder)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-format as Markdown", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (state.autoFormat) "Headings, lists, links and tables" else "Keep the text as it is",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.autoFormat && state.canFormat,
                onCheckedChange = vm::setAutoFormat,
                enabled = state.canFormat && !state.loading,
                modifier = Modifier.testTag(AUTO_FORMAT_TAG)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !state.showSource, onClick = { vm.setShowSource(false) }, label = { Text("Preview") })
            FilterChip(selected = state.showSource, onClick = { vm.setShowSource(true) }, label = { Text("Markdown") })
        }
        if (state.skippedImages > 0) {
            Text(
                "${state.skippedImages} image(s) could not be added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    HorizontalDivider()
    Box(modifier.fillMaxWidth()) {
        if (state.showSource) {
            SelectionContainer {
                Text(
                    state.body,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp)
                        .testTag(SOURCE_TAG)
                )
            }
        } else {
            MarkdownPreview(state.body, vm.previewRoot, index = null, onNoteLink = {})
        }
    }
}

@Composable
private fun FolderPicker(folder: String, folders: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 8.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Text(
                folderLabel(folder),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose folder")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            folders.forEach {
                DropdownMenuItem(
                    text = { Text(folderLabel(it)) },
                    onClick = {
                        onSelect(it)
                        open = false
                    }
                )
            }
        }
    }
}

private fun folderLabel(folder: String) = if (folder.isEmpty()) "MarkNotes" else folder

const val SAVE_TAG = "import-save"
const val TITLE_TAG = "import-title"
const val AUTO_FORMAT_TAG = "import-auto-format"
const val SOURCE_TAG = "import-source"
const val ERROR_TAG = "import-error"
