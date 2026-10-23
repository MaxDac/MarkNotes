package io.github.maxdac.marknotes.proton

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptedNameTest {
    @Test
    fun decryptedNamesAreUsed() {
        assertEquals("n.md", decryptedName(Result.success("n.md"), "a"))
    }

    @Test
    fun anUndecryptableNameFailsInsteadOfBeingSkipped() {
        val error = runCatching { decryptedName(Result.failure(IllegalStateException("bad key")), "a/b") }
        val exception = error.exceptionOrNull()
        assertTrue(exception is IOException)
        assertTrue(exception!!.message!!.contains("/a/b"))
    }
}
