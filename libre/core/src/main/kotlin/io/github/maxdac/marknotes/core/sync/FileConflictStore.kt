package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.sync.Side
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps the pending [SyncConflict]s in a JSON [file] so the merge-back UI (and the base text it needs for a
 * three-way merge) survives a restart.
 */
class FileConflictStore(private val file: File) {
    fun load(): List<SyncConflict> {
        if (!file.isFile) return emptyList()
        return try {
            Json.parseToJsonElement(file.readText()).jsonObject["conflicts"]?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonObject)?.toConflict() }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    fun save(conflicts: List<SyncConflict>) {
        if (conflicts.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("Could not delete $file")
            return
        }
        file.parentFile?.mkdirs()
        val json = JsonObject(mapOf("conflicts" to JsonArray(conflicts.map { it.toJson() })))
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.toString())
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun SyncConflict.toJson() = JsonObject(
        mapOf(
            "path" to JsonPrimitive(path),
            "copyPath" to JsonPrimitive(copyPath),
            "winner" to JsonPrimitive(winner.name),
            "baseText" to (baseText?.let(::JsonPrimitive) ?: JsonNull),
            "driveId" to JsonPrimitive(driveId)
        )
    )

    private fun JsonObject.toConflict(): SyncConflict? {
        fun string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        val path = string("path") ?: return null
        val copyPath = string("copyPath") ?: return null
        val winner = Side.entries.firstOrNull { it.name == string("winner") } ?: return null
        val baseText = this["baseText"]?.jsonPrimitive?.contentOrNull
        return SyncConflict(path, copyPath, winner, baseText, string("driveId").orEmpty())
    }
}
