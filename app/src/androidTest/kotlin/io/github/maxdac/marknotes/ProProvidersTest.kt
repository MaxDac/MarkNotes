package io.github.maxdac.marknotes

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxdac.marknotes.ui.login.THIRD_PARTY_DISCLAIMER
import io.github.maxdac.marknotes.ui.settings.NO_DRIVES_TEXT
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the Pro build wires in Proton Drive and OneDrive on top of the shared (libre) app. */
@RunWith(AndroidJUnit4::class)
class ProProvidersTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun settingsAndAddDriveShowAllProviders() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText(NO_DRIVES_TEXT).assertExists()
        compose.onNodeWithText(THIRD_PARTY_DISCLAIMER).assertExists()

        compose.onNodeWithText("Add drive").performScrollTo().performClick()
        compose.onNodeWithText("Proton Drive").assertExists()
        compose.onNodeWithText("Dropbox").assertExists()
        compose.onNodeWithText("OneDrive").assertExists()
    }
}
