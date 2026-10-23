package io.github.maxdac.marknotes.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.core.importing.ImageSink
import io.github.maxdac.marknotes.core.importing.ImportConverter
import io.github.maxdac.marknotes.core.importing.ImportSource
import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.ui.editor.ImageImporter
import io.github.maxdac.marknotes.ui.editor.ImportedImage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val loading: Boolean = true,
    /** A problem that prevents importing at all. */
    val error: String? = null,
    /** A problem saving, shown as a message; the user can try again. */
    val saveError: String? = null,
    val title: String = "",
    val folder: String = "",
    val folders: List<String> = listOf(""),
    val autoFormat: Boolean = true,
    /** False when there is nothing to format (Markdown files, images only). */
    val canFormat: Boolean = true,
    val showSource: Boolean = false,
    val body: String = "",
    val skippedImages: Int = 0,
    val saving: Boolean = false,
    val savedPath: String? = null
)

/**
 * Prepares shared content as a new note: converts it (auto-formatted as Markdown or kept as-is), keeps the
 * images it brings as pending attachments in [previewRoot] for the preview, and only writes the note and the
 * attachments it still references when the user saves.
 */
class ImportViewModel(
    private val container: AppContainer,
    private val reader: SharedContentReader,
    private val imageImporter: ImageImporter,
    private val content: SharedContent,
    val previewRoot: File
) : ViewModel() {
    private val notes = container.notes
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state

    private var prepared: PreparedImport? = null
    private val pending = mutableMapOf<String, ByteArray>()
    private var conversion: Job? = null

    init {
        viewModelScope.launch {
            try {
                val (result, folders) = withContext(Dispatchers.IO) {
                    reader.read(content) to (listOf("") + notes.listFolders())
                }
                prepared = result
                val canFormat = result.source != null && result.source !is ImportSource.Markdown
                _state.update {
                    it.copy(folders = folders, canFormat = canFormat, skippedImages = result.skippedImages)
                }
                convert(initial = true)
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, error = e.message ?: "Could not read the shared content.") }
            }
        }
    }

    fun setTitle(title: String) = _state.update { it.copy(title = title, saveError = null) }

    fun setFolder(folder: String) = _state.update { it.copy(folder = folder, saveError = null) }

    fun setShowSource(show: Boolean) = _state.update { it.copy(showSource = show) }

    fun setAutoFormat(autoFormat: Boolean) {
        if (autoFormat == _state.value.autoFormat) return
        _state.update { it.copy(autoFormat = autoFormat) }
        convert(initial = false)
    }

    fun dismissSaveError() = _state.update { it.copy(saveError = null) }

    private fun convert(initial: Boolean) {
        val result = prepared ?: return
        val autoFormat = _state.value.autoFormat
        conversion?.cancel()
        conversion = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val body = withContext(Dispatchers.Default) { body(result, autoFormat) }
                _state.update {
                    val title = if (initial) {
                        ImportConverter.suggestTitle(body, result.fileName, result.subject)
                    } else {
                        it.title
                    }
                    it.copy(loading = false, body = body, title = title)
                }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, error = e.message ?: "Could not convert the content.") }
            }
        }
    }

    private fun body(result: PreparedImport, autoFormat: Boolean): String {
        val sink = ImageSink { bytes, extension, fileName ->
            try {
                stage(imageImporter.fromBytes(bytes, extension, fileName))
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        val text = result.source?.let { ImportConverter.convert(it, autoFormat, sink) }.orEmpty().trimEnd()
        val images = result.images.joinToString("\n\n") { Attachments.markdown(it.fileName, stage(it)) }
        return listOf(text, images).filter { it.isNotEmpty() }.joinToString("\n\n") + "\n"
    }

    /** Keeps [image] until saving and writes it to the preview folder so the preview can show it. */
    private fun stage(image: ImportedImage): String {
        val path = Attachments.pathFor(image.bytes, image.extension)
        synchronized(pending) {
            if (path !in pending) {
                pending[path] = image.bytes
                File(previewRoot, path).apply { parentFile?.mkdirs() }.writeBytes(image.bytes)
            }
        }
        return path
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.loading || current.error != null) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) { write(current) }
                container.notifyLocalChanged()
                _state.update { it.copy(saving = false, savedPath = path) }
            } catch (e: IOException) {
                _state.update { it.copy(saving = false, saveError = e.message ?: "Could not save the note.") }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(saving = false, saveError = e.message ?: "Invalid note name.") }
            }
        }
    }

    private fun write(state: ImportUiState): String {
        val referenced = Attachments.references(state.body)
        val images = synchronized(pending) { pending.filterKeys { it in referenced } }
        images.forEach { (path, bytes) -> Attachments.save(notes, bytes, path.substringAfterLast('.')) }
        val name =
            uniqueName(state.folder, ImportConverter.sanitize(state.title).ifEmpty { ImportConverter.DEFAULT_TITLE })
        val path = notes.createNote(state.folder, name)
        notes.write(path, notes.read(path) + state.body)
        return path
    }

    private fun uniqueName(folder: String, name: String): String {
        var candidate = name
        var counter = 2
        while (notes.exists(NotePaths.join(folder, candidate + NotePaths.NOTE_EXTENSION))) {
            candidate = "$name $counter"
            counter++
        }
        return candidate
    }

    override fun onCleared() {
        previewRoot.deleteRecursively()
    }
}
