package io.github.maxdac.marknotes

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.about.MarkNotesEdition
import io.github.maxdac.marknotes.about.MarkNotesPeer
import io.github.maxdac.marknotes.ui.MarkNotesTheme
import io.github.maxdac.marknotes.ui.settings.SettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the Pro build lists the libre, Desktop and Web apps in Settings, and not itself. */
@RunWith(AndroidJUnit4::class)
class ProOtherAppsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val expected = mapOf(
        "MarkNotes for Android (F-Droid)" to "https://f-droid.org/packages/io.github.maxdac.marknotes.libre/",
        "MarkNotes Desktop (GitHub Releases)" to "https://github.com/MaxDac/marknotes-electron/releases",
        "MarkNotes Web" to "https://marknotes.fly.dev"
    )

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.awaitWorkspace()
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun proListsExactlyTheOtherThreeApps() {
        assertEquals(MarkNotesEdition.Pro, context.appContainer.edition)
        assertEquals(
            expected.toList(),
            MarkNotesPeer.others(context.appContainer.edition).map { it.label to it.url }
        )
        showSettings { }
        expected.keys.forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(MarkNotesPeer.AndroidPro.label).assertDoesNotExist()
    }

    @Test
    fun eachTapOpensItsExactUrl() {
        val intents = mutableListOf<Intent>()
        showSettings { intents.add(it) }
        compose.waitForIdle()
        assertTrue(intents.isEmpty())

        expected.forEach { (label, url) ->
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.runOnIdle {
                val intent = intents.removeAt(0)
                assertTrue(intents.isEmpty())
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(url, intent.dataString)
            }
        }
    }

    // Override only the platform launch boundary so taps don't open a real browser.
    private fun showSettings(launch: (Intent) -> Unit) {
        scenario.onActivity { activity ->
            val recordingContext = object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent) = launch(intent)
            }
            activity.setContent {
                CompositionLocalProvider(LocalContext provides recordingContext) {
                    MarkNotesTheme {
                        SettingsScreen(onAddDrive = {}, onOpenConflicts = {}, onBack = {})
                    }
                }
            }
        }
    }
}
