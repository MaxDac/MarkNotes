package io.github.maxdac.marknotes.core.sync

import java.io.File
import java.io.IOException

/**
 * Keeps, per drive, the content of every note as it was the last time it was in sync. It is the
 * common ancestor used for three-way merges.
 */
interface BaseContentStore {
    fun get(path: String): ByteArray?
    fun put(path: String, bytes: ByteArray)
    fun delete(path: String)
    fun clear()
}

/** Stores base copies as files under [root], mirroring the note paths. */
class FileBaseContentStore(private val root: File) : BaseContentStore {
    private fun file(path: String) = File(root, path)

    override fun get(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    override fun put(path: String, bytes: ByteArray) {
        val target = file(path)
        val parent = target.parentFile ?: throw IOException("Invalid path: $path")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create folder for $path")
        target.writeBytes(bytes)
    }

    override fun delete(path: String) {
        file(path).deleteRecursively()
    }

    override fun clear() {
        root.deleteRecursively()
    }
}

class InMemoryBaseContentStore : BaseContentStore {
    private val items = HashMap<String, ByteArray>()

    override fun get(path: String): ByteArray? = synchronized(items) { items[path] }

    override fun put(path: String, bytes: ByteArray) {
        synchronized(items) { items[path] = bytes }
    }

    override fun delete(path: String) {
        synchronized(items) { items.keys.removeAll { it == path || it.startsWith("$path/") } }
    }

    override fun clear() {
        synchronized(items) { items.clear() }
    }
}
