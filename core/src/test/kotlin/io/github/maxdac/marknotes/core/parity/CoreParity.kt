package io.github.maxdac.marknotes.core.parity

import io.github.maxdac.marknotes.core.merge.ConflictChoice
import io.github.maxdac.marknotes.core.merge.ThreeWayMerge
import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.core.notes.IndexedNote
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.NoteIds
import io.github.maxdac.marknotes.core.notes.NoteIndex
import io.github.maxdac.marknotes.core.notes.NoteLinks
import io.github.maxdac.marknotes.core.sync.SyncEngine
import io.github.maxdac.marknotes.core.sync.SyncManifest
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** How a marknotes-spec fixture file is run on Android. */
sealed interface ParityMapping {
    /**
     * Runs each case through the real implementation. [pendingSpecIssues] maps case names to unexpected
     * differences reported to marknotes-spec and not yet triaged there (skipped with a TODO).
     */
    class Run(
        val pendingSpecIssues: Map<String, String> = emptyMap(),
        val run: (FixtureCase) -> JsonElement
    ) : ParityMapping

    /** The logic lives outside `:core`; [where] names the test that runs the fixture. */
    class Elsewhere(val where: String) : ParityMapping
}

/** Every marknotes-spec fixture file and the `:core` function it exercises. */
object CoreParity {
    val mappings: Map<String, ParityMapping> = mapOf(
        "note-ids/validation" to ParityMapping.Run { c -> field("valid", NoteIds.isValid(c.string("value"))) },
        "front-matter/read-id" to ParityMapping.Run { c -> field("id", FrontMatter.parse(c.string("text")).id) },
        "front-matter/with-id" to ParityMapping.Run { c ->
            field("text", FrontMatter.withId(c.string("text"), c.string("id")))
        },
        "front-matter/split" to ParityMapping.Run { c ->
            val doc = FrontMatter.parse(c.string("text"))
            JsonObject(mapOf("frontMatter" to JsonPrimitive(doc.header), "body" to JsonPrimitive(doc.body)))
        },
        "note-links/link-markdown" to ParityMapping.Run { c ->
            field("markdown", NoteLinks.markdown(c.string("title"), c.string("id")))
        },
        "note-links/parse-href" to ParityMapping.Run { c -> field("id", NoteLinks.idOf(c.string("href"))) },
        "note-links/filter" to ParityMapping.Run(run = ::filter),
        "note-links/mention" to ParityMapping.Elsewhere(":app io.github.maxdac.marknotes.parity.MentionParityTest"),
        "attachments/extension-for-type" to ParityMapping.Run { c ->
            field("extension", Attachments.extensionFor(c.string("mime")))
        },
        "attachments/is-attachment-path" to ParityMapping.Run { c ->
            field("isAttachment", Attachments.isAttachment(c.string("path")))
        },
        "attachments/image-markdown" to ParityMapping.Run { c ->
            field("markdown", Attachments.markdown(c.string("fileName"), c.string("path")))
        },
        "attachments/scaled-size" to ParityMapping.Run { c ->
            val width = c.int("width")
            val height = c.int("height")
            val scaled = Attachments.scaledSize(width, height)
            buildJsonObject {
                put("width", scaled?.first ?: width)
                put("height", scaled?.second ?: height)
                put("scaled", scaled != null)
            }
        },
        "attachments/refs" to ParityMapping.Run { c ->
            list("refs", Attachments.references(c.string("text")).sorted())
        },
        "manifest/parse" to ParityMapping.Run { c ->
            JsonObject(mapOf("manifest" to (SyncManifest.parse(c.string("json"))?.let(::manifestJson) ?: JsonNull)))
        },
        "manifest/serialize" to ParityMapping.Run { c ->
            val files = c.input.getValue("files").jsonObject.mapValues { (_, value) ->
                val e = value.jsonObject
                SyncManifest.Entry(e.str("sha256"), e.str("version"), e.getValue("syncedAt").jsonPrimitive.long)
            }
            val manifest = SyncManifest(c.string("deviceId"), c.input.getValue("updatedAt").jsonPrimitive.long, files)
            field("json", manifest.toJson())
        },
        "duplicate-ids/resolve" to ParityMapping.Run(run = ::resolveDuplicates),
        "merge/merge3" to ParityMapping.Run { c ->
            val result = ThreeWayMerge.merge(c.stringOrNull("base"), c.string("local"), c.string("remote"))
            val choices = (c.input["choices"] as? JsonObject).orEmpty().entries.associate { (index, choice) ->
                index.toInt() to ConflictChoice.valueOf(choice.jsonPrimitive.content.uppercase())
            }
            buildJsonObject {
                put("text", result.text(choices))
                put("conflicts", result.conflicts.size)
            }
        },
        "merge/conflict-copy-name" to ParityMapping.Run { c ->
            withTempStore { store -> field("path", store.uniqueSibling(c.string("path"), SyncEngine.CONFLICT_SUFFIX)) }
        }
    )

    private fun field(key: String, value: Any?): JsonObject = JsonObject(
        mapOf(
            key to when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        )
    )

    private fun list(key: String, values: List<String>): JsonObject =
        JsonObject(mapOf(key to JsonArray(values.map(::JsonPrimitive))))

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun <T> withTempStore(block: (LocalNoteStore) -> T): T {
        val root = Files.createTempDirectory("marknotes-parity").toFile()
        return try {
            block(LocalNoteStore(root))
        } finally {
            root.deleteRecursively()
        }
    }

    /** The note index as the `@` picker sees it: notes are `<fullPath>.md` files. */
    private fun filter(c: FixtureCase): JsonElement {
        val notes = c.input.getValue("notes").jsonArray.map {
            val note = it.jsonObject
            IndexedNote(note.str("id"), note.str("fullPath") + ".md")
        }
        return list("ids", NoteIndex(notes).search(c.string("query")).map { it.id })
    }

    /** Writes the notes to a temporary store and lists the ones [NoteIndex.build] gives a new id. */
    private fun resolveDuplicates(c: FixtureCase): JsonElement = withTempStore { store ->
        c.input.getValue("notes").jsonArray.forEach {
            val note = it.jsonObject
            store.write(note.str("path"), "---\nid: ${note.str("id")}\n---\n")
        }
        list("reassigned", NoteIndex.build(store, Random(0)).rewritten.sorted())
    }

    private fun manifestJson(manifest: SyncManifest): JsonElement = buildJsonObject {
        put("deviceId", manifest.deviceId)
        put("updatedAt", manifest.updatedAt)
        putJsonObject("files") {
            manifest.files.forEach { (path, entry) ->
                putJsonObject(path) {
                    put("sha256", entry.sha256)
                    put("version", entry.version)
                    put("syncedAt", entry.syncedAt)
                }
            }
        }
    }
}
