package io.github.maxdac.marknotes.proton

import android.util.Log
import me.proton.drive.sdk.LoggerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SdkLogTest {
    private val secret = "uploaded /Notes/diary.md for user abc123"

    @Test
    fun debugBuildsLogInfoAndAboveWithTheMessage() {
        assertNull(sdkLogLine(LoggerProvider.Level.DEBUG, "cat", secret, verbose = true))
        assertEquals(
            SdkLogLine(Log.INFO, "[cat] $secret"),
            sdkLogLine(LoggerProvider.Level.INFO, "cat", secret, verbose = true)
        )
        assertEquals(Log.ERROR, sdkLogLine(LoggerProvider.Level.ERROR, "cat", secret, verbose = true)?.priority)
    }

    @Test
    fun releaseBuildsLogOnlyWarningsAndErrorsWithoutTheMessage() {
        assertNull(sdkLogLine(LoggerProvider.Level.VERBOSE, "cat", secret, verbose = false))
        assertNull(sdkLogLine(LoggerProvider.Level.INFO, "cat", secret, verbose = false))
        val warn = sdkLogLine(LoggerProvider.Level.WARN, "cat", secret, verbose = false)
        val error = sdkLogLine(LoggerProvider.Level.ERROR, "cat", secret, verbose = false)
        assertEquals(Log.WARN, warn?.priority)
        assertEquals(Log.ERROR, error?.priority)
        listOf(warn, error).forEach { line ->
            assertFalse(line!!.text.contains("diary"))
            assertFalse(line.text.contains("abc123"))
        }
    }
}
