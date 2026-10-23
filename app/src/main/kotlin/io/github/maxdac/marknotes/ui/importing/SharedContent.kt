package io.github.maxdac.marknotes.ui.importing

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.Spanned
import androidx.core.content.IntentCompat

/** Text, rich text, files or images another app shared with MarkNotes through the share sheet. */
data class SharedContent(
    val text: String? = null,
    val html: String? = null,
    val subject: String? = null,
    val streams: List<Uri> = emptyList(),
    val mimeType: String? = null
) {
    companion object {
        /** Reads an [Intent.ACTION_SEND] or [Intent.ACTION_SEND_MULTIPLE] intent, or returns null for any other. */
        fun from(intent: Intent): SharedContent? {
            val action = intent.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
            val rich = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.takeIf { it.isNotBlank() }
                ?: (rich as? Spanned)?.takeIf { it.hasSpans() }?.let {
                    Html.toHtml(it, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                }
            val streams = if (action == Intent.ACTION_SEND) {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }.ifEmpty { clipUris(intent) }
            val content = SharedContent(
                text = rich?.toString()?.takeIf { it.isNotBlank() },
                html = html,
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
                streams = streams.distinct(),
                mimeType = intent.type
            )
            return content.takeIf { it.text != null || it.html != null || it.streams.isNotEmpty() }
        }

        private fun clipUris(intent: Intent): List<Uri> {
            val clip = intent.clipData ?: return emptyList()
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }

        private fun Spanned.hasSpans() = getSpans(0, length, Any::class.java).isNotEmpty()
    }
}
