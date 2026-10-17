package io.github.maxdac.marknotes.ui.editor

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
    val text: String = "",
    val preview: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null
)

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
                _state.value = _state.value.copy(loaded = true, text = text, dirty = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loaded = true, error = e.message)
            }
        }
    }

    fun onTextChange(text: String) {
        _state.value = _state.value.copy(text = text, dirty = true)
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
