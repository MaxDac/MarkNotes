package io.github.maxdac.marknotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.auth.KeystoreSessionStorage
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProDiagnosticsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun inheritedDiagnosticsInitializeBeforeProConnectors() {
        assertTrue(context.applicationContext is ProApp)
        assertNotNull(context.appContainer.diagnostics)
    }

    @Test
    fun inheritedReporterUsesProIssueDestinationAndItsOwnPrivateSharingAuthority() = runBlocking<Unit> {
        val diagnostics = context.appContainer.diagnostics
        diagnostics.clear()
        try {
            val privateData = "password-token-account-note-/private/path-https://private.invalid"
            diagnostics.report(DiagnosticCode.SDK_ERROR, IOException(privateData))

            val draft = diagnostics.draft()
            assertTrue(draft.url.startsWith("https://github.com/MaxDac/MarkNotes/issues/new?"))
            assertTrue(draft.url.length <= 2_000)
            assertTrue(draft.url.all { it.code < 128 })
            assertFalse(draft.body.contains(privateData))
            assertTrue(draft.body.contains("SDK_ERROR"))

            val intent = diagnostics.shareIntent()

            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertTrue(text.contains("SDK_ERROR"))
            assertFalse(text.contains(privateData))

            diagnostics.clear()
            assertFalse(diagnostics.draft().body.contains("SDK_ERROR"))
            assertThrows(FileNotFoundException::class.java) {
                context.contentResolver.openInputStream(uri)?.close()
            }
        } finally {
            diagnostics.clear()
        }
    }

    @Test
    fun unreadableProtonSessionReportsOnlyAFixedAuthenticationEventAndIsCleared() {
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val previous = prefs.getString("session", null)
        val events = mutableListOf<Pair<DiagnosticCode, Throwable?>>()
        val storage = KeystoreSessionStorage(
            context,
            diagnostics = DiagnosticReporter { code, failure -> events += code to failure }
        )
        try {
            prefs.edit().putString("session", "invalid-private-session").commit()

            assertNull(storage.load())

            assertEquals(listOf(DiagnosticCode.AUTHENTICATION to null), events)
            assertFalse(prefs.contains("session"))
        } finally {
            prefs.edit().putString("session", previous).commit()
        }
    }
}
