package io.github.maxdac.marknotes

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.auth.KeystoreSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.sync.DropboxConnector
import io.github.maxdac.marknotes.sync.OneDriveConnector
import io.github.maxdac.marknotes.sync.ProtonConnector
import io.github.maxdac.marknotes.sync.SyncManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MarkNotesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container shared by the whole app. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notes = LocalNoteStore(File(context.filesDir, "notes").apply { mkdirs() })
    val settings = AppSettings(context, dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    val authClient = ProtonAuthClient()
    private val database = MarkNotesDatabase.create(context)
    private val credentials = CredentialStore(context)

    val proton = ProtonConnector(context.applicationContext, KeystoreSessionStorage(context), authClient)
    val dropbox = DropboxConnector(credentials)
    private val oneDrive = OneDriveConnector(context.applicationContext, credentials)

    private val _localRevision = MutableStateFlow(0L)

    /** Bumped whenever the note tree changes (browser actions, sync, conflict resolution). */
    val localRevision: StateFlow<Long> = _localRevision

    fun notifyLocalChanged() {
        _localRevision.value = _localRevision.value + 1
    }

    val syncManager = SyncManager(
        context = context.applicationContext,
        notes = notes,
        records = database.syncRecords(),
        accounts = database.driveAccounts(),
        proton = proton,
        connectors = mapOf(
            DriveProvider.PROTON to proton,
            DriveProvider.DROPBOX to dropbox,
            DriveProvider.ONEDRIVE to oneDrive
        ),
        settings = settings,
        scope = appScope,
        onLocalChanged = ::notifyLocalChanged
    )
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MarkNotesApp).container
