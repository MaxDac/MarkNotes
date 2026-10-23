package io.github.maxdac.marknotes.ui.editor

import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.core.notes.IndexedNote
import io.github.maxdac.marknotes.core.notes.NoteIds
import io.github.maxdac.marknotes.core.notes.NoteIndex
import io.github.maxdac.marknotes.core.notes.NoteLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The open `@` note-link popup: the `@` position, the text typed after it and the matching notes. */
data class MentionState(val start: Int, val query: String, val results: List<IndexedNote>, val loading: Boolean)

data class EditorState(
    val loaded: Boolean = false,
    /** The note body; the front-matter is kept aside and never shown. */
    val value: TextFieldValue = TextFieldValue(),
    val preview: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
    val noteId: String? = null,
    val mention: MentionState? = null,
    val index: NoteIndex? = null,
    val importingImage: Boolean = false,
    /** A short, transient message (snackbar). */
    val message: String? = null
) {
    val text: String get() = value.text
}

private fun TextFieldValue.toEditorText() = EditorText(text, selection.start, selection.end)

private fun EditorText.toTextFieldValue() = TextFieldValue(text, TextRange(selectionStart, selectionEnd))

class EditorViewModel(private val container: AppContainer, val path: String) : ViewModel() {
    private val notes = container.notes
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state
    private var saveJob: Job? = null
    private var indexJob: Job? = null

    /** Front-matter block of the note, re-attached to the body on save. */
    @Volatile
    private var header = ""

    private val unregisterSave = container.pendingSaves.register(::saveNow)

    init {
        load()
        // Reload when a sync changed the file, unless there are unsaved edits.
        viewModelScope.launch {
            container.localRevision.drop(1).collect { if (!_state.value.dirty) load() }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    val exists = notes.exists(path)
                    val parsed = FrontMatter.parse(if (exists) notes.read(path) else "")
                    if (parsed.id != null || !exists) {
                        parsed
                    } else {
                        // Legacy note: give it an id now, saved like any other edit so it syncs.
                        FrontMatter.withId(parsed, NoteIds.generate()).also { notes.write(path, it.text) }
                    }
                }
                header = doc.header
                val text = doc.body
                val selection = _state.value.value.selection
                val value = TextFieldValue(
                    text,
                    TextRange(selection.start.coerceAtMost(text.length), selection.end.coerceAtMost(text.length))
                )
                _state.value = _state.value.copy(loaded = true, value = value, dirty = false, noteId = doc.id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loaded = true, error = e.message)
            }
        }
    }

    fun onValueChange(value: TextFieldValue) {
        val old = _state.value.value
        val continued = MarkdownEditing.continueList(old.toEditorText(), value.toEditorText())
        val updated = continued?.toTextFieldValue() ?: value
        update(updated)
        updateMention(old.toEditorText(), updated.toEditorText())
    }

    /** Applies a toolbar formatting [action] to the current text and selection. */
    fun applyFormat(action: (EditorText) -> EditorText) {
        update(action(_state.value.value.toEditorText()).toTextFieldValue())
    }

    private fun update(value: TextFieldValue) {
        val textChanged = value.text != _state.value.text
        _state.value = _state.value.copy(value = value, dirty = _state.value.dirty || textChanged)
        if (!textChanged) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            save()
        }
    }

    private fun updateMention(old: EditorText, new: EditorText) {
        val trigger = MarkdownEditing.typedMentionTrigger(old, new)
        val current = _state.value.mention
        when {
            trigger != null -> {
                val index = _state.value.index
                _state.value = _state.value.copy(
                    mention = MentionState(trigger, "", index?.search("").orEmpty(), loading = true)
                )
                loadIndex(assignIds = true)
            }

            current != null -> {
                val query = MarkdownEditing.mentionQuery(new, current.start)
                val index = _state.value.index
                val results = index?.search(query.orEmpty()).orEmpty()
                val noMatchAfterSpace = query != null && query.endsWith(' ') && !current.loading && results.isEmpty()
                _state.value = _state.value.copy(
                    mention = if (query == null || noMatchAfterSpace) {
                        null
                    } else {
                        current.copy(query = query, results = results)
                    }
                )
            }
        }
    }

    /** Rebuilds the note index; with [assignIds] notes without (or with duplicate) ids get a new one. */
    private fun loadIndex(assignIds: Boolean) {
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            try {
                val build = withContext(Dispatchers.IO) {
                    NoteIndex.build(notes, assignIds = assignIds).also {
                        // This note's id may have been reassigned as a duplicate: keep the new header.
                        if (path in it.rewritten) header = FrontMatter.parse(notes.read(path)).header
                    }
                }
                val mention = _state.value.mention
                _state.value = _state.value.copy(
                    index = build.index,
                    noteId = FrontMatter.idOf(header),
                    mention = mention?.copy(results = build.index.search(mention.query), loading = false)
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    mention = _state.value.mention?.copy(loading = false),
                    error = "Could not list notes: ${e.message}"
                )
            }
        }
    }

    /** Replaces the typed `@query` with a link to [note]. */
    fun selectMention(note: IndexedNote) {
        val mention = _state.value.mention ?: return
        val link = NoteLinks.markdown(note.title, note.id)
        _state.value = _state.value.copy(mention = null)
        val replaced = MarkdownEditing.replaceMention(_state.value.value.toEditorText(), mention.start, link)
        update(replaced.toTextFieldValue())
    }

    fun dismissMention() {
        _state.value = _state.value.copy(mention = null)
    }

    /** Opens the note a `marknotes:` link points to, or reports that it doesn't exist. */
    fun openNoteLink(destination: String, onOpen: (String) -> Unit) {
        val id = NoteLinks.idOf(destination) ?: return
        viewModelScope.launch {
            val index = _state.value.index
                ?: withContext(Dispatchers.IO) { NoteIndex.build(notes, assignIds = false).index }
            val note = index.resolve(id)
            if (note == null) {
                showMessage("Note not found")
            } else if (note.path != path) {
                flush()
                onOpen(note.path)
            }
        }
    }

    /** Stores the image at [uri] as an attachment and inserts a markdown image at the cursor. */
    fun insertImage(uri: Uri, fallbackName: String, mimeHint: String? = null, onDone: () -> Unit = {}) {
        _state.value = _state.value.copy(importingImage = true)
        viewModelScope.launch {
            try {
                val (path, name) = withContext(Dispatchers.IO) {
                    val image = container.imageImporter.import(uri, fallbackName, mimeHint)
                    Attachments.save(notes, image.bytes, image.extension) to image.fileName
                }
                applyFormat { MarkdownEditing.insertBlock(it, Attachments.markdown(name, path)) }
            } catch (e: ImageImportException) {
                _state.value = _state.value.copy(error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Could not add the image: ${e.message}")
            } finally {
                _state.value = _state.value.copy(importingImage = false)
                withContext(Dispatchers.IO + NonCancellable) { onDone() }
            }
        }
    }

    fun togglePreview() {
        val preview = !_state.value.preview
        _state.value = _state.value.copy(preview = preview, mention = null)
        // The preview needs the index to resolve note links and flag broken ones.
        if (preview) loadIndex(assignIds = false)
    }

    private suspend fun save() {
        val snapshot = _state.value
        if (!snapshot.dirty) return
        try {
            withContext(Dispatchers.IO + NonCancellable) { notes.write(path, FrontMatter.join(header, snapshot.text)) }
            if (_state.value.text == snapshot.text) _state.value = _state.value.copy(dirty = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Could not save: ${e.message}")
        }
    }

    /** Writes pending edits now; a sync calls this before it lists the notes. */
    private suspend fun saveNow() = withContext(Dispatchers.Main.immediate) {
        saveJob?.cancel()
        save()
    }

    fun showMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Saves pending edits immediately, e.g. when leaving the screen. */
    fun flush() {
        saveJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.dirty) return
        _state.value = snapshot.copy(dirty = false)
        val content = FrontMatter.join(header, snapshot.text)
        val write = container.appScope.launch(Dispatchers.IO) {
            try {
                notes.write(path, content)
            } catch (e: Exception) {
                // Nothing else to do once the screen is gone.
            }
            container.notifyLocalChanged()
        }
        container.pendingSaves.track(write)
    }

    override fun onCleared() {
        flush()
        unregisterSave()
        super.onCleared()
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 800L
    }
}
