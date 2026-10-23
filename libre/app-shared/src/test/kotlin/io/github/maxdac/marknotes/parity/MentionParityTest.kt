package io.github.maxdac.marknotes.parity

import io.github.maxdac.marknotes.core.parity.FixtureCase
import io.github.maxdac.marknotes.core.parity.SpecFixtures
import io.github.maxdac.marknotes.ui.editor.EditorText
import io.github.maxdac.marknotes.ui.editor.MarkdownEditing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Runs marknotes-spec's `note-links/mention` fixture (mapped as "elsewhere" in `:core`'s CoreParity) against the
 * editor's mention detection: the line is typed one character at a time, like the editor does, so the `@`
 * trigger and the query are tracked by [MarkdownEditing.typedMentionTrigger] and [MarkdownEditing.mentionQuery].
 */
@RunWith(Parameterized::class)
class MentionParityTest(private val case: FixtureCase) {
    @Test
    fun matchesSpec() {
        case.check(detect(case.string("lineBeforeCursor")))
    }

    private fun detect(line: String): JsonElement {
        var value = EditorText("", 0)
        var start: Int? = null
        line.forEach { char ->
            val typed = EditorText(value.text + char, value.text.length + 1)
            MarkdownEditing.typedMentionTrigger(value, typed)?.let { start = it }
            start = start?.takeIf { MarkdownEditing.mentionQuery(typed, it) != null }
            value = typed
        }
        val query = start?.let { MarkdownEditing.mentionQuery(value, it) }
            ?: return buildJsonObject { put("mention", JsonNull) }
        return buildJsonObject {
            putJsonObject("mention") {
                put("from", start)
                put("query", JsonPrimitive(query))
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<FixtureCase> = SpecFixtures.load("note-links/mention")
    }
}
