package io.github.maxdac.marknotes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.ui.browser.NEW_CHORDS_MENU_ITEM_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_FAB_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_MENU_ITEM_TAG
import io.github.maxdac.marknotes.ui.settings.CHORD_SEQUENCES_SWITCH_TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the Pro build keeps chord sequence creation behind the (off by default) Settings switch. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProChordSequencesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val settings get() = InstrumentationRegistry.getInstrumentation().targetContext.appContainer.settings

    @Before
    fun setUp() {
        settings.setChordSequencesEnabled(false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        settings.setChordSequencesEnabled(false)
    }

    @Test
    fun newOffersChordSequenceOnlyAfterTurningTheSettingOn() {
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_CHORDS_MENU_ITEM_TAG).assertDoesNotExist()
        pressBack()
        compose.waitUntilDoesNotExist(hasSetTextAction(), timeoutMillis = 5_000)

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag(CHORD_SEQUENCES_SWITCH_TAG).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag(CHORD_SEQUENCES_SWITCH_TAG).assertIsOn()
        assertTrue(settings.chordSequencesEnabled.value)
        compose.onNodeWithContentDescription("Back").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag(NEW_NOTE_FAB_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(NEW_CHORDS_MENU_ITEM_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_NOTE_MENU_ITEM_TAG).assertExists()
    }
}
