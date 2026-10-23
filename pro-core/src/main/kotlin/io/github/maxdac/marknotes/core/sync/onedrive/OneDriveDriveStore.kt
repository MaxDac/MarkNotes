package io.github.maxdac.marknotes.core.sync.onedrive

import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.core.sync.RemoteChangedException
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import io.github.maxdac.marknotes.core.sync.RemoteFolder
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.SyncManifest
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class GraphException(val httpStatus: Int, message: String) : IOException(message)

/** Minimal Microsoft Graph client for the signed-in user's OneDrive. */
class GraphClient(
    private val http: OkHttpClient,
    private val accessToken: suspend () -> String,
    val baseUrl: String = GRAPH_URL
) {
    /** URL of the item at [path] (relative to the drive root), followed by [suffix] (e.g. "/children"). */
    fun pathUrl(path: String, suffix: String = ""): String {
        val clean = path.trim('/')
        return when {
            clean.isEmpty() -> "$baseUrl/me/drive/root$suffix"
            suffix.isEmpty() -> "$baseUrl/me/drive/root:/${encodePath(clean)}"
            else -> "$baseUrl/me/drive/root:/${encodePath(clean)}:$suffix"
        }
    }

    /** URL of [relativePath] below the item [parentId], followed by [suffix]. */
    fun childPathUrl(parentId: String, relativePath: String, suffix: String = ""): String =
        "$baseUrl/me/drive/items/$parentId:/${encodePath(relativePath)}:$suffix"

    fun itemUrl(id: String, suffix: String = "") = "$baseUrl/me/drive/items/$id$suffix"

    suspend fun getJson(url: String): JsonObject = json(send("GET", url))

    suspend fun send(
        method: String,
        url: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap()
    ): ByteArray {
        val token = accessToken()
        val requestBody = body ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Authorization", "Bearer $token")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    throw GraphException(response.code, errorMessage(response.code, bytes))
                }
                bytes
            }
        }
    }

    suspend fun putContent(url: String, bytes: ByteArray, headers: Map<String, String> = emptyMap()): JsonObject =
        json(send("PUT", url, bytes.toRequestBody(OCTET_STREAM), headers))

    suspend fun postJson(url: String, body: JsonObject): JsonObject =
        json(send("POST", url, body.toString().toRequestBody(JSON_MEDIA)))

    suspend fun patchJson(url: String, body: JsonObject): JsonObject =
        json(send("PATCH", url, body.toString().toRequestBody(JSON_MEDIA)))

    /** Follows "@odata.nextLink" pages and returns every item of a collection. */
    suspend fun collection(url: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var next: String? = url
        while (next != null) {
            val page = getJson(next)
            page["value"]?.jsonArray?.forEach { items += it.jsonObject }
            next = page.string("@odata.nextLink")
        }
        return items
    }

    private fun json(bytes: ByteArray): JsonObject =
        if (bytes.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun errorMessage(code: Int, bytes: ByteArray): String {
        val detail = try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject.obj("error")?.string("message")
        } catch (e: IllegalArgumentException) {
            null
        }
        return when (code) {
            401 -> "OneDrive session expired, please sign in again"
            else -> "OneDrive error $code" + (detail?.let { ": $it" } ?: "")
        }
    }

    companion object {
        const val GRAPH_URL = "https://graph.microsoft.com/v1.0"
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val JSON_MEDIA = "application/json".toMediaType()

        fun encodePath(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
        }
    }
}

internal fun JsonObject.string(key: String): String? = this[key]?.let {
    if (it is JsonObject) null else it.jsonPrimitive.contentOrNull
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Lists OneDrive folders for the folder picker. */
class OneDriveFolderBrowser(private val graph: GraphClient) : RemoteFolderBrowser {
    override suspend fun listFolders(path: String): List<RemoteFolder> =
        graph.collection(graph.pathUrl(path, "/children"))
            .filter { it.obj("folder") != null }
            .mapNotNull { item -> item.string("name")?.let { RemoteFolder(NotePaths.join(path.trim('/'), it), it) } }
            .sortedBy { it.name.lowercase() }
}

/**
 * [RemoteDriveStore] over Microsoft Graph, scoped to [syncFolder] (relative to the OneDrive root).
 *
 * The folder tree is traversed once; afterwards it is kept up to date with the drive's delta feed.
 * The content tag (cTag) is used as the file version; uploads of new revisions and file deletions send the
 * eTag in If-Match so a concurrent remote change is never overwritten or trashed. The modification time is
 * the note's local edit time, kept in `fileSystemInfo.lastModifiedDateTime`.
 */
class OneDriveDriveStore(private val graph: GraphClient, syncFolder: String) : RemoteDriveStore {
    private val folder = syncFolder.trim('/')

    private class Cached(val entry: RemoteEntry, val eTag: String)

    private var rootId: String? = null
    private val byId = LinkedHashMap<String, Cached>()
    private var deltaLink: String? = null

    override suspend fun snapshot(): List<RemoteEntry> {
        val root = ensureRoot()
        val link = deltaLink
        // Until a listing completes the cache is incomplete: drop the token so a failure starts over next time.
        deltaLink = null
        deltaLink = if (link == null) {
            fullTraversal(root)
        } else {
            try {
                applyDelta(link)
            } catch (e: GraphException) {
                // 410 Gone: the delta token expired, start over.
                if (e.httpStatus != HTTP_GONE) throw e
                fullTraversal(root)
            }
        }
        return byId.values.map { it.entry }
    }

    override suspend fun download(entry: RemoteEntry): ByteArray =
        graph.send("GET", graph.itemUrl(entry.id, "/content"))

    override suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long): RemoteEntry {
        val url = graph.childPathUrl(ensureRoot(), path, "/content") +
            "?@microsoft.graph.conflictBehavior=fail"
        return withModifiedTime(graph.putContent(url, bytes), path, modifiedMillis)
    }

    override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long): RemoteEntry {
        val item = graph.putContent(graph.itemUrl(entry.id, "/content"), bytes, ifMatch(entry.id))
        return withModifiedTime(item, entry.path, modifiedMillis)
    }

    /** Stamps the uploaded [item] with the local edit time; the upload still counts if this fails. */
    private suspend fun withModifiedTime(item: JsonObject, path: String, modifiedMillis: Long): RemoteEntry {
        val uploaded = remember(item, path)
        if (modifiedMillis <= 0L) return uploaded
        val body = buildJsonObject {
            putJsonObject("fileSystemInfo") {
                put("lastModifiedDateTime", Instant.ofEpochMilli(modifiedMillis).toString())
            }
        }
        return try {
            remember(graph.patchJson(graph.itemUrl(uploaded.id), body), path)
        } catch (e: GraphException) {
            uploaded
        }
    }

    private fun ifMatch(id: String): Map<String, String> {
        val eTag = byId[id]?.eTag
        return if (eTag.isNullOrEmpty()) emptyMap() else mapOf("If-Match" to eTag)
    }

    override suspend fun createFolder(path: String): RemoteEntry {
        var parentId = ensureRoot()
        var current = ""
        var result: RemoteEntry? = null
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = NotePaths.join(current, segment)
            val known = byId.values.firstOrNull { it.entry.isFolder && it.entry.path == current }?.entry
            val entry = known ?: createChildFolder(parentId, segment, current)
            parentId = entry.id
            result = entry
        }
        return result ?: throw IOException("Cannot create the synced folder itself")
    }

    override suspend fun delete(entry: RemoteEntry) {
        val headers = if (entry.isFolder) emptyMap() else ifMatch(entry.id)
        try {
            graph.send("DELETE", graph.itemUrl(entry.id), headers = headers)
        } catch (e: GraphException) {
            if (e.httpStatus == HTTP_PRECONDITION_FAILED) throw RemoteChangedException(entry.path)
            if (e.httpStatus != HTTP_NOT_FOUND) throw e
        }
        removeTree(entry.id)
    }

    override suspend fun readManifest(): ByteArray? = try {
        graph.send("GET", graph.childPathUrl(ensureRoot(), SyncManifest.PATH, "/content"))
    } catch (e: GraphException) {
        if (e.httpStatus == HTTP_NOT_FOUND) null else throw e
    }

    override suspend fun writeManifest(bytes: ByteArray) {
        graph.putContent(graph.childPathUrl(ensureRoot(), SyncManifest.PATH, "/content"), bytes)
    }

    private fun newFolderBody(name: String) = buildJsonObject {
        put("name", name)
        putJsonObject("folder") {}
        put("@microsoft.graph.conflictBehavior", "fail")
    }

    private suspend fun createChildFolder(parentId: String, name: String, path: String): RemoteEntry {
        val item = try {
            graph.postJson(graph.itemUrl(parentId, "/children"), newFolderBody(name))
        } catch (e: GraphException) {
            if (e.httpStatus != HTTP_CONFLICT) throw e
            graph.getJson(graph.childPathUrl(ensureRoot(), path))
        }
        return remember(item, path)
    }

    /** Returns the id of the synced folder, creating it (and its parents) when missing. */
    private suspend fun ensureRoot(): String {
        rootId?.let { return it }
        val item = try {
            graph.getJson(graph.pathUrl(folder))
        } catch (e: GraphException) {
            if (e.httpStatus != HTTP_NOT_FOUND || folder.isEmpty()) throw e
            createRootFolders()
        }
        return (item.string("id") ?: throw IOException("OneDrive returned a folder without id")).also { rootId = it }
    }

    private suspend fun createRootFolders(): JsonObject {
        var parentPath = ""
        var parent = graph.getJson(graph.pathUrl(""))
        for (segment in folder.split('/')) {
            val path = NotePaths.join(parentPath, segment)
            parent = try {
                graph.getJson(graph.pathUrl(path))
            } catch (notFound: GraphException) {
                if (notFound.httpStatus != HTTP_NOT_FOUND) throw notFound
                val parentId = parent.string("id") ?: throw IOException("OneDrive returned a folder without id")
                graph.postJson(graph.itemUrl(parentId, "/children"), newFolderBody(segment))
            }
            parentPath = path
        }
        return parent
    }

    /** Lists the whole synced folder and returns the delta link to keep once it succeeded. */
    private suspend fun fullTraversal(root: String): String? {
        byId.clear()
        // Take the delta token first: changes made during the traversal are replayed by the next delta.
        val latest = graph.getJson(graph.pathUrl("", "/delta") + "?token=latest")
        val link = latest.string("@odata.deltaLink")
        traverse(root, "")
        return link
    }

    private suspend fun traverse(folderId: String, path: String) {
        for (item in graph.collection(graph.itemUrl(folderId, "/children"))) {
            val name = item.string("name") ?: continue
            val childPath = NotePaths.join(path, name)
            val entry = remember(item, childPath)
            if (entry.isFolder) traverse(entry.id, childPath)
        }
    }

    /** Applies the changes since [link] and returns the delta link to keep once they are all applied. */
    private suspend fun applyDelta(link: String): String? {
        var next: String? = link
        var newLink: String? = null
        val deferred = mutableListOf<JsonObject>()
        while (next != null) {
            val page = graph.getJson(next)
            page["value"]?.jsonArray?.forEach { element ->
                val item = element.jsonObject
                if (!applyDeltaItem(item)) deferred += item
            }
            next = page.string("@odata.nextLink")
            page.string("@odata.deltaLink")?.let { newLink = it }
        }
        // Items listed before their parent folder: retry until nothing more can be placed.
        var progress = true
        while (deferred.isNotEmpty() && progress) {
            val placed = deferred.filter { applyDeltaItem(it) }
            deferred.removeAll(placed)
            progress = placed.isNotEmpty()
        }
        return newLink
    }

    /** Applies one changed item. Returns false when its parent folder is not known (yet). */
    private suspend fun applyDeltaItem(item: JsonObject): Boolean {
        val id = item.string("id")
        val name = item.string("name")
        if (id == null || id == rootId) return true
        if (item.obj("deleted") != null || item.obj("@removed") != null || name == null) {
            removeTree(id)
            return true
        }
        val parentId = item.obj("parentReference")?.string("id")
        val parentEntry = parentId?.let { byId[it]?.entry }?.takeIf { it.isFolder }
        if (parentId != rootId && parentEntry == null) {
            // Outside the synced folder, or moved out of it.
            if (id !in byId) return false
            removeTree(id)
            return true
        }
        val path = NotePaths.join(parentEntry?.path.orEmpty(), name)
        placeItem(id, item, path)
        return true
    }

    private suspend fun placeItem(id: String, item: JsonObject, path: String) {
        val existing = byId[id]
        if (existing != null && existing.entry.isFolder && existing.entry.path != path) {
            renameTree(existing.entry.path, path)
        }
        val entry = remember(item, path)
        if (entry.isFolder && existing == null) traverse(entry.id, path)
    }

    private fun remember(item: JsonObject, path: String): RemoteEntry {
        val id = item.string("id") ?: throw IOException("OneDrive returned an item without id")
        val isFolder = item.obj("folder") != null
        val entry = RemoteEntry(
            path = path,
            id = id,
            isFolder = isFolder,
            version = if (isFolder) "" else item.string("cTag") ?: item.string("eTag").orEmpty(),
            modifiedMillis = parseTime(
                item.obj("fileSystemInfo")?.string("lastModifiedDateTime") ?: item.string("lastModifiedDateTime")
            )
        )
        byId[id] = Cached(entry, item.string("eTag").orEmpty())
        return entry
    }

    private fun removeTree(id: String) {
        val removed = byId.remove(id) ?: return
        if (removed.entry.isFolder) {
            byId.entries.removeAll { NotePaths.isAncestor(removed.entry.path, it.value.entry.path) }
        }
    }

    private fun renameTree(oldPath: String, newPath: String) {
        for ((id, cached) in byId.entries.toList()) {
            if (NotePaths.isAncestor(oldPath, cached.entry.path)) {
                val moved = newPath + cached.entry.path.removePrefix(oldPath)
                byId[id] = Cached(cached.entry.copy(path = moved), cached.eTag)
            }
        }
    }

    private fun parseTime(value: String?): Long = try {
        value?.let { Instant.parse(it).toEpochMilli() } ?: 0L
    } catch (e: DateTimeParseException) {
        0L
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_PRECONDITION_FAILED = 412
        const val HTTP_GONE = 410
    }
}
