package io.github.maxdac.marknotes.core.parity

import io.github.maxdac.marknotes.core.notes.Chord
import io.github.maxdac.marknotes.core.notes.ChordParseResult
import io.github.maxdac.marknotes.core.notes.ChordSequence
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.Instrument
import io.github.maxdac.marknotes.core.notes.StringSelection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The chord-sequence fixtures (`chords/` folder), mapped to [ChordSequences]. */
object ChordParity {
    val mappings: Map<String, ParityMapping> = mapOf(
        "chords/detect" to ParityMapping.Run { c ->
            JsonObject(mapOf("chords" to JsonPrimitive(ChordSequences.isChordSequence(c.string("text")))))
        },
        "chords/parse" to ParityMapping.Run { c ->
            when (val result = ChordSequences.parse(c.string("body"))) {
                is ChordParseResult.Ok -> JsonObject(
                    mapOf(
                        "sequence" to sequenceJson(result.sequence),
                        "error" to JsonNull
                    )
                )

                is ChordParseResult.Error -> JsonObject(
                    mapOf(
                        "sequence" to JsonNull,
                        "error" to buildJsonObject {
                            put("line", result.line)
                            put("message", result.message)
                        }
                    )
                )
            }
        },
        "chords/serialize" to ParityMapping.Run { c ->
            val body = ChordSequences.serialize(sequenceOf(c.input.getValue("sequence")), c.string("lineEnding"))
            JsonObject(mapOf("body" to JsonPrimitive(body)))
        },
        "chords/validate" to ParityMapping.Run { c ->
            val errors = ChordSequences.validate(sequenceOf(c.input.getValue("sequence"))).map {
                buildJsonObject {
                    put("chord", it.chord)
                    put("message", it.message)
                }
            }
            JsonObject(mapOf("errors" to JsonArray(errors)))
        },
        "chords/display-names" to ParityMapping.Run { c ->
            val chords = c.input.getValue("chords").jsonArray.map {
                val chord = it.jsonObject
                Chord(chord.text("baseName"), instrumentOf(chord), emptyList())
            }
            JsonObject(mapOf("displayNames" to JsonArray(ChordSequences.displayNames(chords).map(::JsonPrimitive))))
        }
    )

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun instrumentOf(chord: JsonObject): Instrument = requireNotNull(Instrument.of(chord.text("instrument")))

    private fun sequenceOf(json: JsonElement): ChordSequence {
        val sequence = json.jsonObject
        val chords = sequence.getValue("chords").jsonArray.map { element ->
            val chord = element.jsonObject
            Chord(
                name = chord.text("baseName"),
                instrument = instrumentOf(chord),
                strings = chord.getValue("strings").jsonArray.map { stringOf(it.jsonObject) },
                tags = chord.getValue("tags").jsonArray.map { it.jsonPrimitive.content },
                note = chord.text("note")
            )
        }
        val title = (sequence["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ChordSequence(chords, title)
    }

    private fun stringOf(json: JsonObject): StringSelection = when (json.text("state")) {
        "muted" -> StringSelection.Muted

        "open" -> StringSelection.Open

        "fretted" -> StringSelection.Fretted(
            json.getValue("fret").jsonPrimitive.int,
            json.getValue("finger").jsonPrimitive.int
        )

        else -> error("Unknown string state $json")
    }

    private fun sequenceJson(sequence: ChordSequence): JsonElement = JsonObject(
        mapOf(
            "title" to (sequence.title?.let(::JsonPrimitive) ?: JsonNull),
            "chords" to JsonArray(
                sequence.chords.map { chord ->
                    JsonObject(
                        mapOf(
                            "instrument" to JsonPrimitive(chord.instrument.id),
                            "baseName" to JsonPrimitive(chord.name),
                            "strings" to JsonArray(chord.strings.map(::stringJson)),
                            "tags" to JsonArray(chord.tags.map(::JsonPrimitive)),
                            "note" to JsonPrimitive(chord.note)
                        )
                    )
                }
            )
        )
    )

    private fun stringJson(string: StringSelection): JsonElement = when (string) {
        StringSelection.Muted -> buildJsonObject { put("state", "muted") }

        StringSelection.Open -> buildJsonObject { put("state", "open") }

        is StringSelection.Fretted -> buildJsonObject {
            put("state", "fretted")
            put("fret", string.fret)
            put("finger", string.finger)
        }
    }
}
