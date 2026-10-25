package io.github.maxdac.marknotes

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_FAB_TAG

private const val GETTING_STARTED_SKIP_TAG = "getting-started-skip"

fun ComposeTestRule.awaitWorkspace() {
    waitForIdle()
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasTestTag(NEW_NOTE_FAB_TAG) or hasTestTag(GETTING_STARTED_SKIP_TAG))
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
    waitForIdle()
    if (onAllNodes(hasTestTag(GETTING_STARTED_SKIP_TAG))
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    ) {
        onNodeWithTag(GETTING_STARTED_SKIP_TAG).performClick()
    }
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasTestTag(NEW_NOTE_FAB_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
}
