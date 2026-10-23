package io.github.maxdac.marknotes.libre

import io.github.maxdac.marknotes.MarkNotesApp

/** The libre (F-Droid) app: Dropbox is the only cloud provider. */
class LibreApp : MarkNotesApp() {
    override val dropboxAppKey: String = BuildConfig.DROPBOX_APP_KEY
}
