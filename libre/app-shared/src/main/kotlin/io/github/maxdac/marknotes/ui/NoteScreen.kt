package io.github.maxdac.marknotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.ChordParseResult
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.ui.chords.ChordEditorScreen
import io.github.maxdac.marknotes.ui.chords.describeParseError
import io.github.maxdac.marknotes.ui.editor.EditorScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which editor a note opens in. */
private sealed interface NoteKind {
    data object Chords : NoteKind

    /** The markdown editor; [notice] says why a chord sequence couldn't be opened as chords. */
    data class Markdown(val notice: String? = null) : NoteKind
}

/**
 * Opens the note at [path] in the chord editor when it is a chord sequence (front-matter `type: chords`) whose body
 * parses, else in the markdown editor, with the parse error shown when it is a chord sequence that doesn't parse.
 */
@Composable
fun NoteScreen(path: String, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val container = LocalContext.current.appContainer
    val kind by produceState<NoteKind?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { kindOf(container.notes, path) }.getOrDefault(NoteKind.Markdown())
        }
    }
    when (val current = kind) {
        null -> Box(Modifier.fillMaxSize())

        NoteKind.Chords -> ChordEditorScreen(path = path, onBack = onBack)

        is NoteKind.Markdown -> EditorScreen(
            path = path,
            onBack = onBack,
            onOpenNote = onOpenNote,
            notice = current.notice
        )
    }
}

private fun kindOf(notes: LocalNoteStore, path: String): NoteKind {
    if (!ChordSequences.isChordFile(notes, path)) return NoteKind.Markdown()
    return when (val result = ChordSequences.parse(FrontMatter.parse(notes.read(path)).body)) {
        is ChordParseResult.Ok -> NoteKind.Chords

        is ChordParseResult.Error -> NoteKind.Markdown(
            "This chord sequence can't be shown as chords. ${describeParseError(result)}"
        )
    }
}
