package io.github.maxdac.marknotes

import android.content.Context
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.auth.KeystoreSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.sync.DriveConnector
import io.github.maxdac.marknotes.sync.OneDriveConnector
import io.github.maxdac.marknotes.sync.ProtonConnector

/** MarkNotes Pro: the libre app (Dropbox) plus Proton Drive and OneDrive. */
class ProApp : MarkNotesApp() {
    override val dropboxAppKey: String = BuildConfig.DROPBOX_APP_KEY
    override val issueRepository: String = "MaxDac/MarkNotes"

    override fun extraConnectors(context: Context, credentials: CredentialStore): List<DriveConnector> = listOf(
        ProtonConnector(
            context,
            KeystoreSessionStorage(context, diagnostics = diagnosticReporter),
            ProtonAuthClient(diagnostics = diagnosticReporter),
            diagnosticReporter
        ),
        OneDriveConnector(context, credentials, diagnosticReporter)
    )
}
