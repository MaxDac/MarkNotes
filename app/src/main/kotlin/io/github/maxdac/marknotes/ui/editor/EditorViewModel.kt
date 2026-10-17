package io.github.maxdac.marknotes.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorState(
    val loaded: Boolean = false,
    val value: TextFieldValue = TextFieldValue(),
    val preview: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null
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
                val text = withContext(Dispatchers.IO) { if (notes.exists(path)) notes.read(path) else "" }
                val selection = _state.value.value.selection
                val value = TextFieldValue(
                    text,
                    TextRange(selection.start.coerceAtMost(text.length), selection.end.coerceAtMost(text.length))
                )
                _state.value = _state.value.copy(loaded = true, value = value, dirty = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loaded = true, error = e.message)
            }
        }
    }

    fun onValueChange(value: TextFieldValue) {
        val old = _state.value.value
        val continued = MarkdownEditing.continueList(old.toEditorText(), value.toEditorText())
        update(continued?.toTextFieldValue() ?: value)
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

    fun togglePreview() {
        _state.value = _state.value.copy(preview = !_state.value.preview)
    }

    private suspend fun save() {
        val snapshot = _state.value
        if (!snapshot.dirty) return
        try {
            withContext(Dispatchers.IO + NonCancellable) { notes.write(path, snapshot.text) }
            if (_state.value.text == snapshot.text) _state.value = _state.value.copy(dirty = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Could not save: ${e.message}")
        }
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
        container.appScope.launch(Dispatchers.IO) {
            try {
                notes.write(path, snapshot.text)
            } catch (e: Exception) {
                // Nothing else to do once the screen is gone.
            }
            container.notifyLocalChanged()
        }
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 800L
    }
}
