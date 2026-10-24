package io.github.maxdac.marknotes

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.auth.KeystoreSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonAccountStorageTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("proton-account-storage-test", mode)
    }
    private val prefs get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val a = ProtonSession(
        "uid-a", "access-a", "refresh-a", "alice",
        userId = "user-a", keyPassphrases = mapOf("key" to "secret")
    )
    private val b = ProtonSession("uid-b", "access-b", "refresh-b", "bob", userId = "user-b")

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun legacySessionMigratesOnceKeepingItsIdTokensAndKeys() {
        val storage = KeystoreSessionStorage(context)
        storage.save(a)
        assertTrue(prefs.contains("session"))
        assertEquals(mapOf(MarkNotesDatabase.LEGACY_PROTON_ID to a), storage.loadAll())
        assertFalse(prefs.contains("session"))
        storage.save("second", b)
        assertEquals(
            mapOf(MarkNotesDatabase.LEGACY_PROTON_ID to a, "second" to b),
            KeystoreSessionStorage(context).loadAll()
        )
        val encrypted = prefs.all.values.joinToString()
        listOf("access-a", "refresh-a", "alice", "secret", "access-b").forEach {
            assertFalse(encrypted.contains(it))
        }
    }

    @Test
    fun persistedAccountsRefreshAndRemoveIndependently() {
        val storage = KeystoreSessionStorage(context)
        storage.save("first", a)
        storage.save("second", b)
        val rotated = a.copy(accessToken = "rotated", refreshToken = "rotated-r")
        KeystoreSessionStorage(context).save("first", rotated)
        assertEquals(mapOf("first" to rotated, "second" to b), KeystoreSessionStorage(context).loadAll())
        storage.remove("first")
        assertEquals(mapOf("second" to b), KeystoreSessionStorage(context).loadAll())
        storage.remove("second")
        assertTrue(KeystoreSessionStorage(context).loadAll().isEmpty())
    }

    @Test
    fun interruptedMigrationNeverOverwritesANewerAccountSession() {
        val storage = KeystoreSessionStorage(context)
        storage.save(a)
        val rotated = a.copy(accessToken = "rotated")
        storage.save(MarkNotesDatabase.LEGACY_PROTON_ID, rotated)
        assertEquals(mapOf(MarkNotesDatabase.LEGACY_PROTON_ID to rotated), storage.loadAll())
        assertFalse(prefs.contains("session"))
    }

    @Test
    fun corruptAccountDoesNotClearOtherAccounts() {
        val events = mutableListOf<DiagnosticCode>()
        val storage = KeystoreSessionStorage(context, diagnostics = DiagnosticReporter { code, _ -> events += code })
        storage.save("first", a)
        storage.save("second", b)
        prefs.edit().putString("account:first", "unreadable").commit()
        assertEquals(mapOf("second" to b), storage.loadAll())
        assertEquals(listOf(DiagnosticCode.AUTHENTICATION), events)
        assertNull(prefs.getString("account:first", null))
    }

    @Test
    fun invalidationRetainsOnlyStableIdentityAcrossRestartAndRemovalForgetsIt() {
        val storage = KeystoreSessionStorage(context)
        storage.save("first", a)
        storage.save("second", b)
        storage.invalidate("first")
        val restarted = KeystoreSessionStorage(context)
        assertEquals(mapOf("second" to b), restarted.loadAll())
        assertEquals(mapOf("first" to "user-a", "second" to "user-b"), restarted.loadUserIds())
        restarted.remove("first")
        assertEquals(mapOf("second" to "user-b"), storage.loadUserIds())
        assertEquals(mapOf("second" to b), storage.loadAll())
    }

    @Test
    fun changingTheProviderIdentityUnderAnExistingLocalIdIsRejected() {
        val storage = KeystoreSessionStorage(context)
        storage.save("first", a)
        try {
            storage.save("first", b)
            error("Must not replace another user's credentials")
        } catch (expected: IllegalArgumentException) {
            assertEquals(mapOf("first" to a), storage.loadAll())
            assertEquals(mapOf("first" to "user-a"), storage.loadUserIds())
        }
    }
}
