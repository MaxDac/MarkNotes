package io.github.maxdac.marknotes.proton

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonManifestTest {
    private class FakeDrive : ManifestDrive {
        val nodes = mutableMapOf<Pair<String, String>, ManifestNode>()
        val contents = mutableMapOf<String, ByteArray>()
        val calls = mutableListOf<String>()
        private var next = 0

        override suspend fun child(parent: String, name: String) = nodes[parent to name]

        override suspend fun download(revision: String) = contents.getValue(revision)

        override suspend fun createFolder(parent: String, name: String): ManifestNode {
            calls += "createFolder:$name"
            return ManifestNode("f${next++}", isFolder = true).also { nodes[parent to name] = it }
        }

        override suspend fun uploadNew(parent: String, name: String, bytes: ByteArray) {
            calls += "uploadNew:$name"
            val revision = "r${next++}"
            nodes[parent to name] = ManifestNode("n${next++}", isFolder = false, revision)
            contents[revision] = bytes
        }

        override suspend fun uploadRevision(file: ManifestNode, bytes: ByteArray) {
            calls += "uploadRevision:${file.revision}"
            val revision = "r${next++}"
            nodes.entries.first { it.value == file }.setValue(file.copy(revision = revision))
            contents[revision] = bytes
        }
    }

    private val drive = FakeDrive()
    private val manifest = ProtonManifest(drive)

    @Test
    fun missingManifestReadsAsNull() = runTest {
        assertNull(manifest.read("root"))
    }

    @Test
    fun firstWriteCreatesTheHiddenFolderAndLaterWritesAddRevisions() = runTest {
        manifest.write("root", "one".toByteArray())
        assertEquals(listOf("createFolder:.marknotes", "uploadNew:manifest.json"), drive.calls)
        assertEquals("one", manifest.read("root")!!.decodeToString())

        drive.calls.clear()
        manifest.write("root", "two".toByteArray())
        assertEquals(1, drive.calls.size)
        assertTrue(drive.calls.single().startsWith("uploadRevision:"))
        assertEquals("two", manifest.read("root")!!.decodeToString())
    }

    @Test
    fun aFileInPlaceOfTheHiddenFolderIsAnError() = runTest {
        drive.nodes["root" to ".marknotes"] = ManifestNode("x", isFolder = false, "r")
        assertNull(manifest.read("root"))
        assertTrue(runCatching { manifest.write("root", "x".toByteArray()) }.exceptionOrNull() is IOException)
    }
}
