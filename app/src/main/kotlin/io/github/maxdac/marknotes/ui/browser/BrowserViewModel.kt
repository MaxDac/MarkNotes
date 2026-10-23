package io.github.maxdac.marknotes.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.NoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(private val container: AppContainer, val folder: String) : ViewModel() {
    private val notes = container.notes

    private val _entries = MutableStateFlow<List<NoteEntry>>(emptyList())
    val entries: StateFlow<List<NoteEntry>> = _entries

    private val _chordPaths = MutableStateFlow<Set<String>>(emptySet())

    /** Paths of the [entries] that are chord sequences, shown with their own icon. */
    val chordPaths: StateFlow<Set<String>> = _chordPaths

    private val _folders = MutableStateFlow<List<String>>(emptyList())

    /** Every folder of the note tree, used by the folder drawer and the move dialog. */
    val folders: StateFlow<List<String>> = _folders

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val syncState = container.syncManager.state

    init {
        viewModelScope.launch {
            container.localRevision.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val entries = if (notes.isFolder(folder) || folder.isEmpty()) notes.list(folder) else emptyList()
                _chordPaths.value = entries.filter { !it.isFolder && isChordFile(it.path) }.mapTo(HashSet()) { it.path }
                _entries.value = entries
                _folders.value = notes.listFolders()
            }
        }
    }

    private fun isChordFile(path: String): Boolean =
        runCatching { ChordSequences.isChordFile(notes, path) }.getOrDefault(false)

    fun createNote(name: String, onCreated: (String) -> Unit) = act {
        val path = notes.createNote(folder, name)
        withContext(Dispatchers.Main) { onCreated(path) }
    }

    fun createChordSequence(name: String, onCreated: (String) -> Unit) = act {
        val path = notes.createNote(folder, name, ChordSequences.TYPE)
        withContext(Dispatchers.Main) { onCreated(path) }
    }

    fun createFolder(name: String) = act { notes.createFolder(folder, name) }

    fun rename(entry: NoteEntry, newName: String) = act { notes.rename(entry.path, newName) }

    fun move(entry: NoteEntry, targetFolder: String) = act { notes.move(entry.path, targetFolder) }

    fun delete(entry: NoteEntry) = act { notes.delete(entry.path) }

    fun sync() = container.syncManager.syncNow()

    fun clearError() {
        _error.value = null
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                _error.value = e.message ?: "Operation failed"
            }
            // Refreshes every open folder screen, since moves change two folders.
            container.notifyLocalChanged()
        }
    }
}
