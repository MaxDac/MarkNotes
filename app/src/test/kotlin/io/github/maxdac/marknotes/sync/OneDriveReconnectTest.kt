package io.github.maxdac.marknotes.sync

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDriveReconnectTest {
    private val credentials = mutableMapOf(DRIVE to MSAL_ID)
    private val invalidated = mutableListOf<String>()
    private val discarded = mutableListOf<String>()
    private val reconnect = OneDriveReconnect(
        load = { credentials[it] },
        invalidate = { invalidated += it },
        discard = { discarded += it }
    )

    @Test
    fun theSameAccountKeepsTheCredentialAndDropsTheCachedStore() = runTest {
        var hint: String? = null
        assertTrue(
            reconnect.reconnect(DRIVE) {
                hint = it
                OneDriveSignIn(MSAL_ID, newToMsal = false)
            }
        )
        assertEquals(MSAL_ID, hint)
        assertEquals(mapOf(DRIVE to MSAL_ID), credentials)
        assertEquals(listOf(DRIVE), invalidated)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun anotherAccountIsRejectedAndForgottenWhenOnlyThisSignInAddedIt() = runTest {
        val failure = failure<IllegalStateException> {
            reconnect.reconnect(DRIVE) { OneDriveSignIn("someone-else", newToMsal = true) }
        }
        assertEquals(OneDriveReconnect.WRONG_ACCOUNT, failure.message)
        assertEquals(mapOf(DRIVE to MSAL_ID), credentials)
        assertTrue(invalidated.isEmpty())
        assertEquals(listOf("someone-else"), discarded)
    }

    @Test
    fun anotherDrivesAccountIsRejectedButNotRemoved() = runTest {
        failure<IllegalStateException> {
            reconnect.reconnect(DRIVE) { OneDriveSignIn("other-drive", newToMsal = false) }
        }
        assertEquals(mapOf(DRIVE to MSAL_ID), credentials)
        assertTrue(invalidated.isEmpty())
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun aFailedDiscardStillReportsTheWrongAccount() = runTest {
        val failing = OneDriveReconnect({ credentials[it] }, { invalidated += it }, { throw IOException("offline") })
        val failure = failure<IllegalStateException> {
            failing.reconnect(DRIVE) { OneDriveSignIn("someone-else", newToMsal = true) }
        }
        assertEquals(OneDriveReconnect.WRONG_ACCOUNT, failure.message)
        assertEquals(1, failure.suppressed.size)
        assertEquals(mapOf(DRIVE to MSAL_ID), credentials)
    }

    @Test
    fun cancellingLeavesEverythingAsItWas() = runTest {
        assertFalse(reconnect.reconnect(DRIVE) { null })
        assertEquals(mapOf(DRIVE to MSAL_ID), credentials)
        assertTrue(invalidated.isEmpty())
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun aFailedSignInLeavesTheCredentialsIntact() = runTest {
        failure<IOException> { reconnect.reconnect(DRIVE) { throw IOException("network") } }
        assertEquals(mapOf(DRIVE to MSAL_ID), credentials)
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun aDriveWithoutCredentialsFailsBeforeSigningIn() = runTest {
        var signedIn = false
        val failure = failure<IllegalStateException> {
            reconnect.reconnect("unknown") {
                signedIn = true
                OneDriveSignIn(MSAL_ID, newToMsal = false)
            }
        }
        assertEquals(OneDriveReconnect.MISSING, failure.message)
        assertFalse(signedIn)
    }

    private suspend inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        error("Expected ${T::class.simpleName}")
    }

    private companion object {
        const val DRIVE = "onedrive-1"
        const val MSAL_ID = "msal-home-account"
    }
}
