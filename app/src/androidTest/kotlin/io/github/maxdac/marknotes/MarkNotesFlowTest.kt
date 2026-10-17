package io.github.maxdac.marknotes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_FAB_TAG
import io.github.maxdac.marknotes.ui.editor.EDITOR_FIELD_TAG
import io.github.maxdac.marknotes.ui.editor.PREVIEW_TAG
import io.github.maxdac.marknotes.ui.login.THIRD_PARTY_DISCLAIMER
import io.github.maxdac.marknotes.ui.settings.NO_DRIVES_TEXT
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end UI tests that drive [MainActivity] against the real app-private note store. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MarkNotesFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val notesDir: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "notes")

    @Before
    fun setUp() {
        notesDir.deleteRecursively()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        notesDir.deleteRecursively()
    }

    @Test
    fun emptyLibraryShowsPlaceholder() {
        compose.onNodeWithText("No notes here yet").assertExists()
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).assertExists()
    }

    @Test
    fun createFolderAndNoteInsideIt() {
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Work")
        compose.onNodeWithText("OK").performClick()

        compose.waitUntilExactlyOneExists(hasText("Work"))
        compose.onNodeWithText("Work").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes here yet"))
        compose.onNodeWithContentDescription("Back").assertExists()

        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Meeting")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG))

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Meeting"))
        assertTrue(File(notesDir, "Work").isDirectory)
        assertTrue(File(notesDir, "Work/Meeting.md").isFile)
    }

    @Test
    fun editNoteTogglePreviewAndAutosave() {
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Shopping")
        compose.onNodeWithText("OK").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG))
        compose.onNodeWithTag(EDITOR_FIELD_TAG).performTextInput("# Groceries\n\n- milk")

        compose.onNodeWithContentDescription("Preview").performClick()
        compose.onNodeWithTag(PREVIEW_TAG).assertExists()
        compose.onNodeWithTag(EDITOR_FIELD_TAG).assertDoesNotExist()

        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithTag(EDITOR_FIELD_TAG).assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Shopping"))
        val note = File(notesDir, "Shopping.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && note.readText().isNotEmpty() }
        assertEquals("# Groceries\n\n- milk", note.readText())
    }

    @Test
    fun enterContinuesChecklistAndToolbarFormats() {
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), timeoutMillis = 5_000)
        compose.onNode(hasSetTextAction()).performTextInput("Todo")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG), timeoutMillis = 5_000)

        val editor = compose.onNodeWithTag(EDITOR_FIELD_TAG)
        compose.onNodeWithContentDescription("Checkbox").performClick()
        editor.performTextInput("milk")
        editor.performTextInput("\n")
        editor.performTextInput("eggs")
        editor.performTextInput("\n")
        editor.performTextInput("\n")
        compose.onNodeWithContentDescription("Bold").performClick()
        editor.performTextInput("done")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Todo"))
        val note = File(notesDir, "Todo.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && note.readText().isNotEmpty() }
        assertEquals("- [ ] milk\n- [ ] eggs\n**done**", note.readText())
    }

    @Test
    fun repeatedBackFromSubfolderNeverLeavesBlankScreen() {
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Inbox")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasText("Inbox"))
        compose.onNodeWithText("Inbox").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes here yet"))

        val back = compose.onNodeWithContentDescription("Back")
        back.performClick()
        runCatching { back.performClick() }

        compose.waitUntilExactlyOneExists(hasText("Inbox"))
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun settingsAndAddDriveShowProviders() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText(NO_DRIVES_TEXT).assertExists()
        compose.onNodeWithText(THIRD_PARTY_DISCLAIMER).assertExists()
        compose.onNodeWithText("System").assertExists()
        compose.onNodeWithText("Dark").assertExists()

        compose.onNodeWithText("Add drive").performClick()
        compose.onNodeWithText("Proton Drive").assertExists()
        compose.onNodeWithText("Dropbox").assertExists()
        compose.onNodeWithText("OneDrive").assertExists()
    }
}
