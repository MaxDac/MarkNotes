package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.NotePaths

/** In-memory [RemoteDriveStore] used by tests. */
class FakeRemoteDriveStore : RemoteDriveStore {
    private class Item(val entry: RemoteEntry, val bytes: ByteArray)

    private val items = linkedMapOf<String, Item>()
    private var counter = 0
    var calls = mutableListOf<String>()
        private set

    /** Paths whose [uploadNew] fails. */
    val failingUploads = mutableSetOf<String>()

    /** Paths whose [uploadRevision] fails. */
    val failingRevisions = mutableSetOf<String>()

    /** Runs once after the next [snapshot] is taken, e.g. to simulate another device or an editor save. */
    var afterSnapshot: (() -> Unit)? = null

    /** Runs before every [download], e.g. to simulate an editor save racing the sync. */
    var beforeDownload: ((RemoteEntry) -> Unit)? = null

    /** Bytes served instead of the stored ones by [download], keyed by path. */
    val corruptDownloads = mutableMapOf<String, ByteArray>()

    private fun nextId() = "id-${++counter}"
    private fun nextVersion() = "v-${++counter}"

    fun putFile(path: String, content: String, modifiedMillis: Long = 0L): RemoteEntry {
        ensureParents(path)
        val existing = items[path]
        val entry = RemoteEntry(path, existing?.entry?.id ?: nextId(), false, nextVersion(), modifiedMillis)
        items[path] = Item(entry, content.toByteArray())
        return entry
    }

    fun putFolder(path: String) {
        ensureParents(path)
        if (path !in items) items[path] = Item(RemoteEntry(path, nextId(), true, "", 0L), ByteArray(0))
    }

    fun remove(path: String) {
        items.keys.filter { it == path || NotePaths.isAncestor(path, it) }.forEach { items.remove(it) }
    }

    fun content(path: String): String? = items[path]?.takeIf { !it.entry.isFolder }?.bytes?.toString(Charsets.UTF_8)

    fun exists(path: String) = path in items

    fun isFolder(path: String) = items[path]?.entry?.isFolder == true

    private fun ensureParents(path: String) {
        var parent = NotePaths.parent(path)
        val missing = mutableListOf<String>()
        while (parent.isNotEmpty() && parent !in items) {
            missing += parent
            parent = NotePaths.parent(parent)
        }
        missing.reversed().forEach { items[it] = Item(RemoteEntry(it, nextId(), true, "", 0L), ByteArray(0)) }
    }

    fun entry(path: String): RemoteEntry? = items[path]?.entry

    override suspend fun snapshot(): List<RemoteEntry> {
        val entries = items.values.map { it.entry }
        afterSnapshot?.also { afterSnapshot = null }?.invoke()
        return entries
    }

    override suspend fun download(entry: RemoteEntry): ByteArray {
        calls += "download:${entry.path}"
        beforeDownload?.invoke(entry)
        corruptDownloads[entry.path]?.let { return it }
        return items[entry.path]?.bytes ?: error("Not found: ${entry.path}")
    }

    override suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long): RemoteEntry {
        calls += "uploadNew:$path"
        if (path in failingUploads) error("upload refused")
        check(path !in items) { "Already exists: $path" }
        ensureParents(path)
        val entry = RemoteEntry(path, nextId(), false, nextVersion(), modifiedMillis)
        items[path] = Item(entry, bytes)
        return entry
    }

    override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long): RemoteEntry {
        calls += "uploadRevision:${entry.path}"
        if (entry.path in failingRevisions) error("upload refused")
        val current = items[entry.path] ?: error("Not found: ${entry.path}")
        val updated = current.entry.copy(version = nextVersion(), modifiedMillis = modifiedMillis)
        items[entry.path] = Item(updated, bytes)
        return updated
    }

    override suspend fun createFolder(path: String): RemoteEntry {
        calls += "createFolder:$path"
        putFolder(path)
        return items.getValue(path).entry
    }

    override suspend fun delete(entry: RemoteEntry) {
        calls += "delete:${entry.path}"
        val current = items[entry.path]?.entry ?: return
        if (!entry.isFolder && current.version != entry.version) throw RemoteChangedException(entry.path)
        remove(entry.path)
    }

    var manifest: ByteArray? = null
    var manifestWrites = 0
        private set

    override suspend fun readManifest(): ByteArray? = manifest

    override suspend fun writeManifest(bytes: ByteArray) {
        manifest = bytes
        manifestWrites++
    }
}
