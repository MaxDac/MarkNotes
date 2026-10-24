package io.github.maxdac.marknotes.proton

import android.util.Log
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import me.proton.drive.sdk.LoggerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdkLogTest {
    private val events = mutableListOf<Pair<DiagnosticCode, Throwable?>>()
    private val diagnostics = DiagnosticReporter { code, failure -> events += code to failure }

    @Test
    fun ignoresNonFailureEvents() {
        assertNull(sdkLogLine(LoggerProvider.Level.VERBOSE, diagnostics))
        assertNull(sdkLogLine(LoggerProvider.Level.DEBUG, diagnostics))
        assertNull(sdkLogLine(LoggerProvider.Level.INFO, diagnostics))
        assertEquals(emptyList<Pair<DiagnosticCode, Throwable?>>(), events)
    }

    @Test
    fun emitsOnlyFixedTextForWarningsAndErrorsInEveryBuild() {
        assertEquals(
            SdkLogLine(Log.WARN, "Proton Drive SDK WARN (details omitted)"),
            sdkLogLine(LoggerProvider.Level.WARN, diagnostics)
        )
        assertEquals(
            SdkLogLine(Log.ERROR, "Proton Drive SDK ERROR (details omitted)"),
            sdkLogLine(LoggerProvider.Level.ERROR, diagnostics)
        )
        assertEquals(listOf(DiagnosticCode.SDK_ERROR to null, DiagnosticCode.SDK_ERROR to null), events)
    }
}
