package io.github.maxdac.marknotes

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        scenario = compose.launchWorkspace()
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun diagnosticsRequireReviewBeforeOpeningTheIssueDraft() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Report a problem").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Review problem report").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Review problem report").assertExists()
        compose.onNodeWithText("Opening the draft sends this excerpt to GitHub", substring = true).assertExists()
        compose.onNodeWithText("The issue is public after you submit it.", substring = true).assertExists()
        compose.onNodeWithText("Open draft in browser").assertExists()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Review problem report").assertDoesNotExist()
        compose.onNodeWithText("Export full sanitized logs").assertExists()
        compose.onNodeWithText("Clear logs").assertExists()
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
