package io.github.maxdac.marknotes.core.notes

/** A stringed instrument a chord is voiced for; [tuning] lists the strings from lowest to highest. */
enum class Instrument(val id: String, val label: String, val tuning: List<String>) {
    GUITAR("guitar", "Guitar", listOf("E2", "A2", "D3", "G3", "B3", "E4")),
    BASS("bass", "Bass", listOf("E1", "A1", "D2", "G2"));

    val strings: Int get() = tuning.size

    companion object {
        fun of(id: String): Instrument? = entries.firstOrNull { it.id == id }
    }
}

/** What one string does in a chord. */
sealed interface StringSelection {
    data object Muted : StringSelection

    data object Open : StringSelection

    data class Fretted(val fret: Int, val finger: Int) : StringSelection

    val sounds: Boolean get() = this != Muted
}

/**
 * One voicing of a chord sequence: [strings] has one entry per string of [instrument], lowest first.
 * [name] is the spec's `baseName`; the displayed sequence number is derived, see [ChordSequences.displayNames].
 */
data class Chord(
    val name: String,
    val instrument: Instrument,
    val strings: List<StringSelection>,
    val tags: List<String> = emptyList(),
    val note: String = ""
)

/** The chords of a chord-sequence note in file order, plus the optional `# title`. */
data class ChordSequence(val chords: List<Chord> = emptyList(), val title: String? = null)

sealed interface ChordParseResult {
    data class Ok(val sequence: ChordSequence) : ChordParseResult

    /** The body isn't a valid chord sequence; [line] is 1-based within the body. */
    data class Error(val line: Int, val message: String) : ChordParseResult
}

/** A validation error; [chord] is the 0-based chord index, or null for the title. */
data class ChordIssue(val chord: Int?, val message: String)

/**
 * Chord-sequence notes, as specified in spec/note-format.md, "Chord sequences": a normal note whose
 * front-matter has `type: chords` and whose body is
 *
 * ```
 * # Title
 *
 * ## Am7 (guitar)
 * frets: x 0 2/2 0 1/1 0
 * tags: jazz, open
 *
 * Note text.
 * ```
 *
 * `frets` lists every string lowest first: `x` muted, `0` open, `<fret>/<finger>` fretted.
 */
object ChordSequences {
    const val TYPE = "chords"
    const val MIN_FRET = 1
    const val MAX_FRET = 24
    const val MIN_FINGER = 1
    const val MAX_FINGER = 4
    const val MIN_SOUNDING_STRINGS = 3
    const val MAX_NAME_LENGTH = 80
    const val MAX_NOTE_LENGTH = 1000
    const val MAX_TAGS = 50
    const val MAX_TAG_LENGTH = 80

    /** Frets shown in the mini diagram. */
    const val PREVIEW_FRETS = 4

    /** First fret the editor offers on bass, as in ChordBuilder. */
    const val BASS_FIRST_FRET = 9

    /** The fret highlighted with a red line on bass. */
    const val RED_LINE_FRET = 12

    val markerFrets: Set<Int> = setOf(3, 5, 7, 9, 12, 15, 17, 19, 21, 24)

    private const val FENCE = "---"
    private const val TITLE = "# "
    private const val HEADING = "## "
    private const val FRETS_KEY = "frets:"
    private const val TAGS_KEY = "tags:"
    private const val HEAD_BYTES = 4096
    private const val ASCII_WHITESPACE = "\t\n\u000B\u000C\r "
    private val typeLine = Regex("^type:[ \\t]+(.*)$")
    private val frettedToken = Regex("^([0-9]{1,2})/([0-9])$")
    private val tokenSeparator = Regex("[ \\t]+")
    private val whitespaceRun = Regex("[\\t\\n\\u000B\\u000C\\r ]+")

    /**
     * Whether [text] (a whole note) is a chord sequence: its front-matter block's first `type:` line has the value
     * `chords`. The opening fence may have trailing spaces or tabs.
     */
    fun isChordSequence(text: String): Boolean {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        if (lines.size < 2 || lines[0].trimEnd(' ', '\t') != FENCE) return false
        var type: String? = null
        for (line in lines.drop(1)) {
            if (line.trimEnd() == FENCE) return type == TYPE
            if (type == null) type = typeValue(line)
        }
        return false
    }

    /** The value of a front-matter `type:` line, or null when [line] is not one. */
    fun typeValue(line: String): String? {
        val raw = typeLine.find(line)?.groupValues?.get(1)?.trim(' ', '\t') ?: return null
        val quoted = raw.length >= 2 && raw.first() == raw.last() && raw.first() in "\"'"
        return if (quoted) raw.substring(1, raw.length - 1) else raw
    }

    /** Whether the note at [path] is a chord sequence, reading only the start of the file. */
    fun isChordFile(store: LocalNoteStore, path: String): Boolean {
        val file = store.file(path)
        if (!file.isFile || !NotePaths.isNote(path)) return false
        val head = file.inputStream().use { it.readNBytes(HEAD_BYTES) }.toString(Charsets.UTF_8)
        return isChordSequence(head)
    }

    /** The line ending the chord editor writes for a note whose front-matter block is [header]. */
    fun lineEnding(header: String): String {
        val end = header.indexOf('\n')
        return if (end > 0 && header[end - 1] == '\r') "\r\n" else "\n"
    }

    /** Parses a chord-sequence body into its normalised sequence, or the first syntax error. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(body: String): ChordParseResult {
        val lines = body.split('\n').map { it.removeSuffix("\r") }
        var index = 0
        var title: String? = null
        var first = true
        while (index < lines.size && !lines[index].startsWith(HEADING)) {
            val line = lines[index]
            if (!isBlank(line)) {
                if (!first ||
                    !line.startsWith(TITLE)
                ) {
                    return ChordParseResult.Error(index + 1, "Expected a chord heading.")
                }
                title = trimAscii(line.removePrefix(TITLE)).ifEmpty { null }
                first = false
            }
            index++
        }
        val chords = mutableListOf<Chord>()
        while (index < lines.size) {
            val headingLine = index
            val (name, instrument) = parseHeading(lines[index])
                ?: return ChordParseResult.Error(index + 1, "Invalid chord heading.")
            val fretsLine = nextNonBlank(lines, index + 1)
            if (fretsLine == null || !lines[fretsLine].startsWith(FRETS_KEY)) {
                return ChordParseResult.Error(headingLine + 1, "Chord has no frets line.")
            }
            val strings = mutableListOf<StringSelection>()
            for (token in lines[fretsLine].removePrefix(FRETS_KEY).split(tokenSeparator).filter { it.isNotEmpty() }) {
                strings += parseToken(token)
                    ?: return ChordParseResult.Error(fretsLine + 1, "Invalid string \"$token\".")
            }
            index = fretsLine + 1
            var tags = emptyList<String>()
            val tagsLine = nextNonBlank(lines, index)
            if (tagsLine != null && lines[tagsLine].startsWith(TAGS_KEY)) {
                tags = normalizeTags(lines[tagsLine].removePrefix(TAGS_KEY).split(','))
                index = tagsLine + 1
            }
            val note = mutableListOf<String>()
            while (index < lines.size && !lines[index].startsWith(HEADING)) note += lines[index++]
            chords += Chord(name, instrument, strings, tags, normalizeNote(note.joinToString("\n")))
        }
        return ChordParseResult.Ok(ChordSequence(chords, title))
    }

    private fun parseHeading(line: String): Pair<String, Instrument>? {
        val content = line.removePrefix(HEADING).trimEnd(' ', '\t')
        val instrument = Instrument.entries.firstOrNull { content.endsWith("(${it.id})") } ?: return null
        return normalizeName(content.removeSuffix("(${instrument.id})")) to instrument
    }

    private fun parseToken(token: String): StringSelection? = when (token) {
        "x" -> StringSelection.Muted

        "0" -> StringSelection.Open

        else -> frettedToken.find(token)?.let {
            StringSelection.Fretted(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
    }

    private fun nextNonBlank(lines: List<String>, from: Int): Int? = (from until lines.size).firstOrNull {
        !isBlank(lines[it])
    }

    /** The canonical body for [sequence], with every line ended by [lineEnding]; "" for an empty sequence. */
    fun serialize(sequence: ChordSequence, lineEnding: String = "\n"): String {
        val lines = mutableListOf<String>()
        normalizeTitle(sequence.title)?.let { lines += TITLE + it }
        sequence.chords.forEach { chord ->
            if (lines.isNotEmpty()) lines += ""
            lines += "$HEADING${normalizeName(chord.name)} (${chord.instrument.id})"
            lines += FRETS_KEY + chord.strings.joinToString("") { " " + token(it) }
            val tags = normalizeTags(chord.tags)
            val note = normalizeNote(chord.note)
            if (tags.isNotEmpty()) {
                lines += "$TAGS_KEY ${tags.joinToString(", ")}"
            } else if (note.startsWith(TAGS_KEY)) {
                lines += TAGS_KEY
            }
            if (note.isNotEmpty()) {
                lines += ""
                lines += note.split('\n')
            }
        }
        return lines.joinToString("") { it + lineEnding }
    }

    /** The `frets:` value, e.g. `x 3/3 2/2 0 1/1 0`. */
    fun fretsText(strings: List<StringSelection>): String = strings.joinToString(" ", transform = ::token)

    private fun token(string: StringSelection): String = when (string) {
        StringSelection.Muted -> "x"
        StringSelection.Open -> "0"
        is StringSelection.Fretted -> "${string.fret}/${string.finger}"
    }

    /** Every validation error of [sequence], in spec order: the title, then each chord. */
    fun validate(sequence: ChordSequence): List<ChordIssue> = buildList {
        if (sequence.title?.any { it == '\n' || it == '\r' } == true) {
            add(ChordIssue(null, "Title must be a single line."))
        }
        sequence.chords.forEachIndexed { index, chord -> validate(chord).forEach { add(ChordIssue(index, it)) } }
    }

    /** Problems that keep [chord] from being saved, as user-facing messages; empty when valid. */
    fun validate(chord: Chord): List<String> = buildList {
        val name = normalizeName(chord.name)
        val note = normalizeNote(chord.note)
        val tags = normalizeTags(chord.tags)
        val fretted = chord.strings.filterIsInstance<StringSelection.Fretted>()
        if (name.isEmpty()) add("Enter a chord name.")
        if (length(name) > MAX_NAME_LENGTH) add("Chord names must be at most $MAX_NAME_LENGTH characters.")
        if (chord.strings.size != chord.instrument.strings) {
            add("${chord.instrument.id} requires ${chord.instrument.strings} strings.")
        }
        if (chord.strings.count { it.sounds } < MIN_SOUNDING_STRINGS) add("Select at least three non-muted strings.")
        if (fretted.any { it.fret !in MIN_FRET..MAX_FRET }) add("Frets must be between $MIN_FRET and $MAX_FRET.")
        if (fretted.any { it.finger !in MIN_FINGER..MAX_FINGER }) {
            add("Fingers must be between $MIN_FINGER and $MAX_FINGER.")
        }
        if (length(note) > MAX_NOTE_LENGTH) add("Notes must be at most $MAX_NOTE_LENGTH characters.")
        if (note.split('\n').any { it.startsWith(HEADING) }) add("Note lines cannot start with \"## \".")
        if (tags.size > MAX_TAGS) add("Use at most $MAX_TAGS tags.")
        if (tags.any { length(it) > MAX_TAG_LENGTH }) add("Tags must be at most $MAX_TAG_LENGTH characters.")
        if (tags.any { tag -> tag.any { it == ',' || it == '\n' || it == '\r' } }) {
            add("Tags cannot contain commas or line breaks.")
        }
    }

    /** A chord with all strings muted, the starting point of a new voicing. */
    fun emptyStrings(instrument: Instrument): List<StringSelection> = List(instrument.strings) { StringSelection.Muted }

    /** Trims and collapses ASCII whitespace runs to one space. */
    fun normalizeName(name: String): String = trimAscii(name).replace(whitespaceRun, " ")

    /** Trims the title; an empty title is no title. */
    fun normalizeTitle(title: String?): String? = title?.let(::trimAscii)?.ifEmpty { null }

    /** Converts CRLF to LF and trims ASCII whitespace; inner lines are kept. */
    fun normalizeNote(note: String): String = trimAscii(note.replace("\r\n", "\n"))

    /** Trims tags and drops empty and case-insensitive duplicate ones, keeping the first spelling and order. */
    fun normalizeTags(tags: List<String>): List<String> {
        val unique = LinkedHashMap<String, String>()
        tags.map(::trimAscii).filter { it.isNotEmpty() }.forEach { unique.putIfAbsent(it.lowercase(), it) }
        return unique.values.toList()
    }

    /**
     * `<n> <name>` for each chord, where n counts the chords with the same instrument and lowercased name 1, 2, 3...
     * in file order. Numbers are derived, never stored.
     */
    fun displayNames(chords: List<Chord>): List<String> {
        val counts = HashMap<Pair<String, Instrument>, Int>()
        return chords.map { chord ->
            val name = normalizeName(chord.name)
            val key = name.lowercase() to chord.instrument
            val number = (counts[key] ?: 0) + 1
            counts[key] = number
            "$number $name"
        }
    }

    /** First fret of the [PREVIEW_FRETS]-fret window the mini diagram shows. */
    fun previewStart(strings: List<StringSelection>): Int {
        val frets = strings.filterIsInstance<StringSelection.Fretted>().map { it.fret }
        if (frets.isEmpty()) return 1
        val min = frets.min()
        val max = frets.max()
        return if (max - min < PREVIEW_FRETS) min else maxOf(1, max - PREVIEW_FRETS + 1)
    }

    /** Frets the editor offers for [instrument]: 1-24 on guitar, 9-24 on bass (as in ChordBuilder). */
    fun fretRange(instrument: Instrument): IntRange = when (instrument) {
        Instrument.GUITAR -> MIN_FRET..MAX_FRET
        Instrument.BASS -> BASS_FIRST_FRET..MAX_FRET
    }

    private fun isBlank(line: String): Boolean = line.all { it in ASCII_WHITESPACE }

    private fun trimAscii(text: String): String = text.trim { it in ASCII_WHITESPACE }

    private fun length(text: String): Int = text.codePointCount(0, text.length)
}
