package io.github.maxdac.marknotes.ui.chords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.Chord
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.Instrument
import io.github.maxdac.marknotes.core.notes.NotePaths

const val CHORD_LIST_TAG = "chord-list"
const val CHORD_TEXT_TAG = "chord-text"
const val CHORD_NAME_FIELD_TAG = "chord-name"
const val ADD_CHORD_FAB_TAG = "add-chord-fab"

/** An action that discards unsaved draft changes, so it asks first. */
private sealed interface ChordConfirm {
    data object DiscardDraft : ChordConfirm
    data object ClearDraft : ChordConfirm
    data class SwitchInstrument(val instrument: Instrument) : ChordConfirm
    data class Delete(val index: Int, val name: String) : ChordConfirm
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChordEditorScreen(path: String, onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val vm: ChordEditorViewModel = viewModel { ChordEditorViewModel(container, path) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<ChordConfirm?>(null) }

    DisposableEffect(vm) {
        onDispose { vm.flush() }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    val closeDraft = {
        if (state.draftDirty) confirm = ChordConfirm.DiscardDraft else vm.closeDraft()
    }
    BackHandler(enabled = state.draft != null, onBack = closeDraft)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    val draft = state.draft
                    Text(
                        when {
                            draft == null -> NotePaths.displayName(path)
                            draft.index == null -> "New chord"
                            else -> "Edit ${state.displayNames.getOrElse(draft.index) { draft.name }}"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.draft != null) {
                            closeDraft()
                        } else {
                            vm.flush()
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.loaded && state.draft == null) {
                        IconButton(onClick = vm::toggleTextMode) {
                            if (state.textMode) {
                                Icon(Icons.Filled.MusicNote, contentDescription = "View as chords")
                            } else {
                                Icon(Icons.Filled.Code, contentDescription = "View as text")
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.loaded && state.draft == null && !state.textMode) {
                ExtendedFloatingActionButton(
                    onClick = vm::newChord,
                    modifier = Modifier.testTag(ADD_CHORD_FAB_TAG),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add chord") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val draft = state.draft
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                draft != null -> ChordBuilder(
                    draft = draft,
                    errors = state.draftErrors,
                    vm = vm,
                    onSwitchInstrument = { instrument ->
                        when {
                            instrument == draft.instrument -> Unit
                            state.draftDirty -> confirm = ChordConfirm.SwitchInstrument(instrument)
                            else -> vm.switchInstrument(instrument)
                        }
                    },
                    onClear = { confirm = ChordConfirm.ClearDraft },
                    onCancel = closeDraft
                )

                state.textMode -> ChordText(state.text, state.parseError, vm::onTextChange)

                else -> ChordList(
                    chords = state.chords,
                    names = state.displayNames,
                    vm = vm,
                    onDelete = { index -> confirm = ChordConfirm.Delete(index, state.displayNames[index]) }
                )
            }
        }
    }

    confirm?.let { action ->
        ConfirmDialog(action, onDismiss = { confirm = null }) {
            confirm = null
            when (action) {
                ChordConfirm.DiscardDraft -> vm.closeDraft()
                ChordConfirm.ClearDraft -> vm.clearDraft()
                is ChordConfirm.SwitchInstrument -> vm.switchInstrument(action.instrument)
                is ChordConfirm.Delete -> vm.delete(action.index)
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }
        )
    }
}

@Composable
private fun ConfirmDialog(action: ChordConfirm, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, text, button) = when (action) {
        ChordConfirm.DiscardDraft -> Triple(
            "Discard changes?",
            "The chord you are editing has unsaved changes.",
            "Discard"
        )

        ChordConfirm.ClearDraft -> Triple(
            "Clear the chord?",
            "The name, tags, note and fretboard will be emptied.",
            "Clear"
        )

        is ChordConfirm.SwitchInstrument -> Triple(
            "Switch to ${action.instrument.label.lowercase()}?",
            "The fretboard selection will be cleared.",
            "Switch"
        )

        is ChordConfirm.Delete -> Triple(
            "Delete ${action.name}?",
            "The chord will be removed from the sequence.",
            "Delete"
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(button) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChordList(chords: List<Chord>, names: List<String>, vm: ChordEditorViewModel, onDelete: (Int) -> Unit) {
    if (chords.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, Modifier.padding(bottom = 16.dp))
            Text("No chords yet", style = MaterialTheme.typography.titleMedium)
            Text("Add a chord to start the sequence.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(CHORD_LIST_TAG),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(chords) { index, chord ->
            ChordCard(
                chord = chord,
                name = names[index],
                isFirst = index == 0,
                isLast = index == chords.lastIndex,
                onEdit = { vm.editChord(index) },
                onDuplicate = { vm.duplicate(index) },
                onMove = { vm.move(index, it) },
                onDelete = { onDelete(index) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChordCard(
    chord: Chord,
    name: String,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val invalid = ChordSequences.validate(chord).isNotEmpty()
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit), colors = CardDefaults.cardColors()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        chord.instrument.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (invalid) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Invalid chord",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                ChordDiagram(
                    chord.instrument,
                    chord.strings,
                    description = "$name: ${ChordSequences.fretsText(chord.strings)}"
                )
                if (chord.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chord.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
                if (chord.note.isNotBlank()) Text(chord.note, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for $name")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Edit", onDismiss = { menu = false }, onClick = onEdit)
                    MenuItem("Duplicate", onDismiss = { menu = false }, onClick = onDuplicate)
                    MenuItem("Move up", enabled = !isFirst, onDismiss = { menu = false }) { onMove(-1) }
                    MenuItem("Move down", enabled = !isLast, onDismiss = { menu = false }) { onMove(1) }
                    MenuItem("Delete", onDismiss = { menu = false }, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onDismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = {
        onDismiss()
        onClick()
    })
}

@Composable
private fun ChordBuilder(
    draft: ChordDraft,
    errors: List<String>,
    vm: ChordEditorViewModel,
    onSwitchInstrument: (Instrument) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Instrument.entries.forEach { instrument ->
                FilterChip(
                    selected = draft.instrument == instrument,
                    onClick = { onSwitchInstrument(instrument) },
                    label = { Text(instrument.label) }
                )
            }
        }
        OutlinedTextField(
            value = draft.name,
            onValueChange = { name -> vm.updateDraft { it.copy(name = name.take(ChordSequences.MAX_NAME_LENGTH)) } },
            label = { Text("Chord name *") },
            placeholder = { Text("e.g. Cmaj7") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(CHORD_NAME_FIELD_TAG)
        )
        OutlinedTextField(
            value = draft.tags,
            onValueChange = { tags -> vm.updateDraft { it.copy(tags = tags) } },
            label = { Text("Tags (comma separated)") },
            placeholder = { Text("e.g. jazz, open") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.note,
            onValueChange = { note -> vm.updateDraft { it.copy(note = note.take(ChordSequences.MAX_NOTE_LENGTH)) } },
            label = { Text("Note") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Tap × to mute, O for an open string or a fret to place a finger. Select at least three non-muted strings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Fretboard(draft.instrument, draft.strings, onChange = vm::setString)
        Text(
            "Frets: ${ChordSequences.fretsText(draft.strings)}" +
                if (draft.tagList.isEmpty()) "" else "  ·  Tags: ${draft.tagList.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall
        )
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { vm.saveDraft() }) { Text(if (draft.index == null) "Add" else "Update") }
        }
    }
}

@Composable
private fun ChordText(text: String, parseError: String?, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (parseError != null) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text(parseError, Modifier.padding(start = 12.dp))
                }
            }
        }
        TextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxSize().testTag(CHORD_TEXT_TAG),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}
