package io.github.maxdac.marknotes

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.ui.browser.EMPTY_NEW_CHORDS_TAG
import io.github.maxdac.marknotes.ui.browser.EMPTY_NEW_NOTE_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_CHORDS_MENU_ITEM_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_FAB_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_MENU_ITEM_TAG
import io.github.maxdac.marknotes.ui.chords.ADD_CHORD_FAB_TAG
import io.github.maxdac.marknotes.ui.chords.CHORD_LIST_TAG
import io.github.maxdac.marknotes.ui.chords.CHORD_NAME_FIELD_TAG
import io.github.maxdac.marknotes.ui.chords.CHORD_TEXT_TAG
import io.github.maxdac.marknotes.ui.chords.FRETBOARD_TAG
import io.github.maxdac.marknotes.ui.editor.EDITOR_FIELD_TAG
import io.github.maxdac.marknotes.ui.editor.EDITOR_NOTICE_TAG
import io.github.maxdac.marknotes.ui.editor.PREVIEW_TAG
import io.github.maxdac.marknotes.ui.login.THIRD_PARTY_DISCLAIMER
import io.github.maxdac.marknotes.ui.settings.NO_DRIVES_TEXT
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /** The note text without its (hidden) front-matter. */
    private fun bodyOf(note: File) = FrontMatter.parse(note.readText()).body

    /** Opens the floating "New" menu and picks the item with [itemTag]. */
    private fun newFromFab(itemTag: String) {
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(itemTag), timeoutMillis = 5_000)
        compose.onNodeWithTag(itemTag).performClick()
    }

    @Test
    fun emptyLibraryShowsPlaceholder() {
        compose.onNodeWithText("No notes here yet").assertExists()
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).assertExists()
        compose.onNodeWithTag(EMPTY_NEW_NOTE_TAG).assertExists()
        compose.onNodeWithTag(EMPTY_NEW_CHORDS_TAG).assertExists()
    }

    @Test
    fun fabMenuOffersNoteAndChordSequence() {
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(NEW_NOTE_MENU_ITEM_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_CHORDS_MENU_ITEM_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), timeoutMillis = 5_000)
        compose.onNode(hasSetTextAction()).performTextInput("Tune")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(ADD_CHORD_FAB_TAG), timeoutMillis = 5_000)
    }

    @Test
    fun emptyFolderButtonsCreateNoteAndChordSequence() {
        compose.onNodeWithTag(EMPTY_NEW_CHORDS_TAG).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Riffs")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(ADD_CHORD_FAB_TAG), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Riffs"))
        assertTrue(ChordSequences.isChordSequence(File(notesDir, "Riffs.md").readText()))

        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Empty")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasText("Empty"))
        compose.onNodeWithText("Empty").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EMPTY_NEW_NOTE_TAG))
        compose.onNodeWithTag(EMPTY_NEW_NOTE_TAG).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Plain")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG), timeoutMillis = 5_000)
        assertTrue(File(notesDir, "Empty/Plain.md").isFile)
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

        newFromFab(NEW_NOTE_MENU_ITEM_TAG)
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
        newFromFab(NEW_NOTE_MENU_ITEM_TAG)
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
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && bodyOf(note).isNotEmpty() }
        assertNotNull(FrontMatter.parse(note.readText()).id)
        assertEquals("# Groceries\n\n- milk", bodyOf(note))
    }

    @Test
    fun enterContinuesChecklistAndToolbarFormats() {
        newFromFab(NEW_NOTE_MENU_ITEM_TAG)
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
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && bodyOf(note).isNotEmpty() }
        assertEquals("- [ ] milk\n- [ ] eggs\n**done**", bodyOf(note))
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
    fun createChordSequenceAddChordAndReopen() {
        newFromFab(NEW_CHORDS_MENU_ITEM_TAG)
        compose.onNode(hasSetTextAction()).performTextInput("Song")
        compose.onNodeWithText("OK").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag(ADD_CHORD_FAB_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(ADD_CHORD_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(FRETBOARD_TAG), timeoutMillis = 5_000)
        // Semantic clicks don't depend on where the nested scroll containers leave each cell on small screens.
        listOf("A2 fret 3", "D3 fret 2", "Open G3", "B3 fret 1", "Open E4").forEach { cell ->
            compose.onNodeWithContentDescription(cell).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        compose.waitUntilExactlyOneExists(hasText("Frets: x 3/1 2/1 0 1/1 0", substring = true), timeoutMillis = 5_000)
        compose.onNodeWithTag(CHORD_NAME_FIELD_TAG).performScrollTo().performTextInput("C")
        compose.onNodeWithText("Add").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilExactlyOneExists(hasTestTag(CHORD_LIST_TAG), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("1 C: x 3/1 2/1 0 1/1 0").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Song"))
        val note = File(notesDir, "Song.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && bodyOf(note).isNotEmpty() }
        assertTrue(ChordSequences.isChordSequence(note.readText()))
        assertEquals("## C (guitar)\nfrets: x 3/1 2/1 0 1/1 0\n", bodyOf(note))

        compose.onNodeWithText("Song").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(CHORD_LIST_TAG), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("View as text").performClick()
        compose.onNodeWithTag(CHORD_TEXT_TAG).assertExists()
    }

    @Test
    fun unparsableChordFileOpensInMarkdownEditor() {
        scenario.close()
        notesDir.mkdirs()
        File(notesDir, "Broken.md").writeText("---\ntype: chords\n---\n<<<<<<< ours\n## C (guitar)\n")
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntilExactlyOneExists(hasText("Broken"), timeoutMillis = 5_000)
        compose.onNodeWithText("Broken").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_NOTICE_TAG), timeoutMillis = 5_000)
        compose.onNodeWithText("Line 1", substring = true).assertExists()
        compose.onNodeWithTag(EDITOR_FIELD_TAG).assertExists()
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
