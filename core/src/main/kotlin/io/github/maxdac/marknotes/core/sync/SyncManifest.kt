package io.github.maxdac.marknotes.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Map of the notes stored in a drive, written to [PATH] inside the synced folder after each sync.
 *
 * It lets another device that connects the same drive recognise notes it already has (same content
 * hash) without downloading them, and documents which device last wrote the folder.
 */
data class SyncManifest(
    val deviceId: String,
    val updatedAt: Long,
    val files: Map<String, Entry>
) {
    data class Entry(val sha256: String, val version: String, val syncedAt: Long)

    fun toJson(): String = buildJsonObject {
        put("format", FORMAT_VERSION)
        put("app", "MarkNotes")
        put("deviceId", deviceId)
        put("updatedAt", updatedAt)
        putJsonObject("files") {
            for ((path, entry) in files.toSortedMap()) {
                putJsonObject(path) {
                    put("sha256", entry.sha256)
                    put("version", entry.version)
                    put("syncedAt", entry.syncedAt)
                }
            }
        }
    }.toString()

    /** True when both manifests describe the same files, ignoring who wrote them and when. */
    fun sameFilesAs(other: SyncManifest?): Boolean =
        other != null && files.mapValues { it.value.sha256 to it.value.version } ==
            other.files.mapValues { it.value.sha256 to it.value.version }

    companion object {
        const val DIRECTORY = ".marknotes"
        const val PATH = "$DIRECTORY/manifest.json"
        const val FORMAT_VERSION = 1

        /** Parses a manifest; returns null for unreadable or newer-format manifests. */
        fun parse(json: String): SyncManifest? = try {
            val obj = Json.parseToJsonElement(json).jsonObject
            if (obj.getValue("format").jsonPrimitive.int > FORMAT_VERSION) {
                null
            } else {
                SyncManifest(
                    deviceId = obj.getValue("deviceId").jsonPrimitive.content,
                    updatedAt = obj.getValue("updatedAt").jsonPrimitive.long,
                    files = (obj["files"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                        val e = value.jsonObject
                        Entry(
                            sha256 = e.getValue("sha256").jsonPrimitive.content,
                            version = e.getValue("version").jsonPrimitive.content,
                            syncedAt = e.getValue("syncedAt").jsonPrimitive.long
                        )
                    }
                )
            }
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: NoSuchElementException) {
            null
        }
    }
}
