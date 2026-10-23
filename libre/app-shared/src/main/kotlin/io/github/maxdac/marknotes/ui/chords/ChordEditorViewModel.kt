package io.github.maxdac.marknotes.ui.chords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.core.notes.Chord
import io.github.maxdac.marknotes.core.notes.ChordParseResult
import io.github.maxdac.marknotes.core.notes.ChordSequence
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.core.notes.Instrument
import io.github.maxdac.marknotes.core.notes.NoteIds
import io.github.maxdac.marknotes.core.notes.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The chord being built in the editor form; [index] is the chord it replaces, or null for a new one. */
data class ChordDraft(
    val index: Int? = null,
    val instrument: Instrument = Instrument.GUITAR,
    val name: String = "",
    val tags: String = "",
    val note: String = "",
    val strings: List<StringSelection> = ChordSequences.emptyStrings(instrument)
) {
    val tagList: List<String> get() = ChordSequences.normalizeTags(tags.split(','))

    fun toChord() = Chord(
        ChordSequences.normalizeName(name),
        instrument,
        strings,
        tagList,
        ChordSequences.normalizeNote(note)
    )

    companion object {
        fun of(chord: Chord, index: Int?) = ChordDraft(
            index = index,
            instrument = chord.instrument,
            name = chord.name,
            tags = chord.tags.joinToString(", "),
            note = chord.note,
            strings = chord.strings
        )
    }
}

data class ChordEditorState(
    val loaded: Boolean = false,
    val sequence: ChordSequence = ChordSequence(),
    /** The note body as text, shown in text mode. */
    val text: String = "",
    val textMode: Boolean = false,
    /** Why the body couldn't be read as chords; the editor then stays in text mode. */
    val parseError: String? = null,
    val draft: ChordDraft? = null,
    val draftBaseline: ChordDraft? = null,
    val draftErrors: List<String> = emptyList(),
    val error: String? = null,
    val message: String? = null
) {
    val chords: List<Chord> get() = sequence.chords
    val displayNames: List<String> get() = ChordSequences.displayNames(sequence.chords)
    val draftDirty: Boolean get() = draft != null && draft != draftBaseline
}

/** Edits a chord-sequence note (see [ChordSequences]); the front-matter is kept aside and written back as is. */
class ChordEditorViewModel(private val container: AppContainer, val path: String) : ViewModel() {
    private val notes = container.notes
    private val _state = MutableStateFlow(ChordEditorState())
    val state: StateFlow<ChordEditorState> = _state
    private val writeLock = Mutex()
    private var saveJob: Job? = null

    /** Body waiting to be written, or null when the file is up to date. */
    @Volatile
    private var pending: String? = null

    @Volatile
    private var wrote = false

    @Volatile
    private var header = ""

    private val unregisterSave = container.pendingSaves.register(::saveNow)

    init {
        load()
        // Reload when a sync changed the file, unless there are unsaved edits.
        viewModelScope.launch {
            container.localRevision.drop(1).collect {
                if (pending == null && _state.value.draft == null) load()
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    val parsed = FrontMatter.parse(if (notes.exists(path)) notes.read(path) else "")
                    if (parsed.id != null) {
                        parsed
                    } else {
                        FrontMatter.withId(parsed, NoteIds.generate()).also { notes.write(path, it.text) }
                    }
                }
                header = doc.header
                _state.value = when (val result = ChordSequences.parse(doc.body)) {
                    is ChordParseResult.Ok -> _state.value.copy(
                        loaded = true,
                        sequence = result.sequence,
                        text = doc.body,
                        parseError = null
                    )

                    is ChordParseResult.Error -> _state.value.copy(
                        loaded = true,
                        text = doc.body,
                        textMode = true,
                        parseError = describe(result)
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(loaded = true, error = e.message)
            }
        }
    }

    fun newChord() {
        val instrument = _state.value.chords.lastOrNull()?.instrument ?: Instrument.GUITAR
        openDraft(ChordDraft(instrument = instrument))
    }

    fun editChord(index: Int) {
        val chord = _state.value.chords.getOrNull(index) ?: return
        openDraft(ChordDraft.of(chord, index))
    }

    private fun openDraft(draft: ChordDraft) {
        _state.value = _state.value.copy(draft = draft, draftBaseline = draft, draftErrors = emptyList())
    }

    fun updateDraft(transform: (ChordDraft) -> ChordDraft) {
        val draft = _state.value.draft ?: return
        _state.value = _state.value.copy(draft = transform(draft), draftErrors = emptyList())
    }

    /** Switches the draft to [instrument]; the fretboard starts over since the strings differ. */
    fun switchInstrument(instrument: Instrument) = updateDraft {
        if (it.instrument == instrument) {
            it
        } else {
            it.copy(instrument = instrument, strings = ChordSequences.emptyStrings(instrument))
        }
    }

    fun setString(string: Int, selection: StringSelection) = updateDraft {
        it.copy(strings = it.strings.toMutableList().also { list -> list[string] = selection })
    }

    /** Empties the form, keeping the instrument and the chord being edited. */
    fun clearDraft() = updateDraft { ChordDraft(index = it.index, instrument = it.instrument) }

    fun closeDraft() {
        _state.value = _state.value.copy(draft = null, draftBaseline = null, draftErrors = emptyList())
    }

    /** Validates the draft and puts it in the sequence; returns whether it was saved. */
    fun saveDraft(): Boolean {
        val draft = _state.value.draft ?: return false
        val chord = draft.toChord()
        val errors = ChordSequences.validate(chord)
        if (errors.isNotEmpty()) {
            _state.value = _state.value.copy(draftErrors = errors)
            return false
        }
        val chords = _state.value.chords.toMutableList()
        val index = draft.index?.takeIf { it in chords.indices }
        if (index != null) chords[index] = chord else chords += chord
        val position = index ?: chords.lastIndex
        closeDraft()
        setChords(chords)
        showMessage("Saved ${ChordSequences.displayNames(chords)[position]}")
        return true
    }

    fun duplicate(index: Int) {
        val chords = _state.value.chords.toMutableList()
        val chord = chords.getOrNull(index) ?: return
        chords.add(index + 1, chord)
        setChords(chords)
    }

    fun delete(index: Int) {
        val chords = _state.value.chords.toMutableList()
        if (index !in chords.indices) return
        val name = _state.value.displayNames[index]
        chords.removeAt(index)
        setChords(chords)
        showMessage("Deleted $name")
    }

    /** Moves the chord at [index] by [offset] positions, e.g. -1 for up. */
    fun move(index: Int, offset: Int) {
        val chords = _state.value.chords.toMutableList()
        val target = index + offset
        if (index !in chords.indices || target !in chords.indices) return
        chords.add(target, chords.removeAt(index))
        setChords(chords)
    }

    private fun setChords(chords: List<Chord>) {
        val sequence = _state.value.sequence.copy(chords = chords)
        val body = ChordSequences.serialize(sequence, ChordSequences.lineEnding(header))
        _state.value = _state.value.copy(sequence = sequence, text = body)
        save(body, debounce = false)
    }

    /** Switches between the chord editor and the plain text of the note. */
    fun toggleTextMode() {
        val current = _state.value
        if (current.draft != null) return
        if (!current.textMode) {
            _state.value = current.copy(textMode = true)
            return
        }
        _state.value = when (val result = ChordSequences.parse(current.text)) {
            is ChordParseResult.Ok -> current.copy(textMode = false, sequence = result.sequence, parseError = null)
            is ChordParseResult.Error -> current.copy(parseError = describe(result))
        }
    }

    fun onTextChange(text: String) {
        _state.value = _state.value.copy(text = text)
        save(text, debounce = true)
    }

    private fun save(body: String, debounce: Boolean) {
        pending = body
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (debounce) delay(AUTOSAVE_DELAY_MS)
            write()
        }
    }

    private suspend fun write() {
        withContext(Dispatchers.IO + NonCancellable) {
            writeLock.withLock {
                val body = pending ?: return@withLock
                try {
                    notes.write(path, FrontMatter.join(header, body))
                    wrote = true
                    if (pending == body) pending = null
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = "Could not save: ${e.message}")
                }
            }
        }
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

    /** Saves pending edits now and lets the rest of the app (sync, browser) know, e.g. when leaving. */
    fun flush() {
        saveJob?.cancel()
        val write = container.appScope.launch {
            write()
            if (wrote) {
                wrote = false
                container.notifyLocalChanged()
            }
        }
        container.pendingSaves.track(write)
    }

    /** Writes pending edits now; a sync calls this before it lists the notes. */
    private suspend fun saveNow() {
        saveJob?.cancel()
        write()
    }

    override fun onCleared() {
        flush()
        unregisterSave()
        super.onCleared()
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 800L

        fun describe(error: ChordParseResult.Error) = describeParseError(error)
    }
}

/** A parse error as shown to the user, e.g. `Line 3: Invalid chord heading.` */
fun describeParseError(error: ChordParseResult.Error) = "Line ${error.line}: ${error.message}"
