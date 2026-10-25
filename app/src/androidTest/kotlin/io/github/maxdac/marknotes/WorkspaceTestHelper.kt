package io.github.maxdac.marknotes

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_FAB_TAG

/**
 * Launches [MainActivity] on the note list. The first-run guide is marked as seen beforehand, as Libre's flow tests
 * do, so a dismissing guide dialog can't race the test's first interactions; Libre's GettingStartedFlowTest covers it.
 */
fun ComposeTestRule.launchWorkspace(): ActivityScenario<MainActivity> {
    InstrumentationRegistry.getInstrumentation().targetContext.appContainer.settings.setGettingStartedConsumed(true)
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    waitForIdle()
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasTestTag(NEW_NOTE_FAB_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
    return scenario
}
