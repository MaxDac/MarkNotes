package io.github.maxdac.marknotes.core.notes

import io.github.maxdac.marknotes.core.sync.Hashing
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AttachmentsTest {
    private lateinit var dir: File
    private lateinit var store: LocalNoteStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("attachments").toFile()
        store = LocalNoteStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun mimeTypes() {
        assertEquals("png", Attachments.extensionFor("image/png"))
        assertEquals("jpg", Attachments.extensionFor("IMAGE/JPEG"))
        assertEquals("gif", Attachments.extensionFor("image/gif"))
        assertEquals("webp", Attachments.extensionFor("image/webp"))
        assertNull(Attachments.extensionFor("image/heic"))
        assertNull(Attachments.extensionFor(null))
        assertEquals("image/jpeg", Attachments.mimeFor("attachments/x.jpg"))
        assertNull(Attachments.mimeFor("note.md"))
    }

    @Test
    fun attachmentPaths() {
        assertTrue(Attachments.isAttachment("attachments/abc.png"))
        assertFalse(Attachments.isAttachment("attachments/abc.md"))
        assertFalse(Attachments.isAttachment("attachments/sub/abc.png"))
        assertFalse(Attachments.isAttachment("notes/attachments/abc.png"))
        assertFalse(Attachments.isAttachment("abc.png"))
        assertTrue(Attachments.isInFolder("attachments"))
        assertTrue(Attachments.isInFolder("attachments/sub/x.md"))
        assertFalse(Attachments.isInFolder("attachmentsx/a.md"))
    }

    @Test
    fun saveNamesFilesAfterTheirHash() {
        val bytes = byteArrayOf(1, 2, 3)
        val path = Attachments.save(store, bytes, "png")
        assertEquals("attachments/${Hashing.sha256Hex(bytes)}.png", path)
        assertEquals(bytes.toList(), store.readBytes(path).toList())
        assertEquals(path, Attachments.save(store, bytes, "png"))
        assertThrows(IllegalArgumentException::class.java) { Attachments.save(store, bytes, "bmp") }
        assertThrows(IllegalArgumentException::class.java) {
            Attachments.save(store, ByteArray((Attachments.MAX_BYTES + 1).toInt()), "png")
        }
    }

    @Test
    fun markdownUsesFileNameWithoutExtension() {
        assertEquals(
            "![Holiday photo](attachments/x.jpg)",
            Attachments.markdown("Holiday photo.jpeg", "attachments/x.jpg")
        )
        assertEquals("![scan](attachments/x.png)", Attachments.markdown("scan", "attachments/x.png"))
    }

    @Test
    fun scaledSizeKeepsAspectRatio() {
        assertNull(Attachments.scaledSize(2048, 1000))
        assertEquals(2048 to 1536, Attachments.scaledSize(4000, 3000))
        assertEquals(1024 to 2048, Attachments.scaledSize(2000, 4000))
    }

    @Test
    fun cleanUnusedDeletesOnlyUnreferencedAttachments() {
        val used = Attachments.save(store, byteArrayOf(1), "png")
        val unused = Attachments.save(store, byteArrayOf(2), "jpg")
        store.write("a/n.md", "---\nid: aaaaaaaa\n---\n![x]($used)")

        assertEquals(listOf(unused), Attachments.unused(store))
        assertEquals(listOf(unused), Attachments.deleteUnused(store))
        assertTrue(store.exists(used))
        assertFalse(store.exists(unused))
    }
}
