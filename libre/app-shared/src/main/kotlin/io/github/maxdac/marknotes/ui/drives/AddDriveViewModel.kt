package io.github.maxdac.marknotes.ui.drives

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteFolder
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.sync.SignedInAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderOption(val provider: DriveProvider, val unavailableReason: String?)

sealed interface AddDriveStep {
    data object ChooseProvider : AddDriveStep
    data class SigningIn(val provider: DriveProvider) : AddDriveStep

    /**
     * Picking the folder to sync. [folders] lists the sub-folders of [path] and is null while loading.
     * Without [browsable] (Proton) the folder name is typed instead.
     */
    data class ChooseFolder(
        val account: SignedInAccount,
        val browsable: Boolean,
        val path: String = "",
        val folders: List<RemoteFolder>? = null
    ) : AddDriveStep
}

data class AddDriveState(
    val step: AddDriveStep = AddDriveStep.ChooseProvider,
    val createRootFolder: Boolean = true,
    val rootFolderName: String = DriveAccount.DEFAULT_ROOT_FOLDER,
    val error: String? = null,
    /** Set when the provider's in-app sign-in screen must be shown before signing in. */
    val signInScreenFor: DriveProvider? = null,
    val done: Boolean = false
)

class AddDriveViewModel(container: AppContainer) : ViewModel() {
    private val syncManager = container.syncManager
    private val _state = MutableStateFlow(AddDriveState())
    val state: StateFlow<AddDriveState> = _state

    private var browser: RemoteFolderBrowser? = null
    private var signedIn: SignedInAccount? = null
    private var awaitingSignInScreen: DriveProvider? = null

    fun providers(): List<ProviderOption> {
        val drives = syncManager.state.value.drives
        return syncManager.providers.map { provider ->
            val connector = syncManager.connector(provider)
            val reason = connector.unavailableReason
                ?: if (connector.singleAccount && drives.any { it.account.provider == provider }) {
                    "Already connected"
                } else {
                    null
                }
            ProviderOption(provider, reason)
        }
    }

    fun choose(provider: DriveProvider, activity: ComponentActivity) {
        if (syncManager.connector(provider).needsSignInScreen()) {
            awaitingSignInScreen = provider
            _state.update { it.copy(signInScreenFor = provider) }
            return
        }
        signIn(provider, activity)
    }

    fun onSignInScreenShown() = _state.update { it.copy(signInScreenFor = null) }

    /** Called whenever the screen is shown; continues after the provider's sign-in screen succeeded. */
    fun onShown(activity: ComponentActivity) {
        val provider = awaitingSignInScreen ?: return
        if (!syncManager.connector(provider).needsSignInScreen()) {
            awaitingSignInScreen = null
            signIn(provider, activity)
        }
    }

    private fun signIn(provider: DriveProvider, activity: ComponentActivity) {
        _state.update { it.copy(step = AddDriveStep.SigningIn(provider), error = null) }
        viewModelScope.launch {
            try {
                val connector = syncManager.connector(provider)
                val account = connector.signIn(activity)
                if (account == null) {
                    _state.update { it.copy(step = AddDriveStep.ChooseProvider) }
                    return@launch
                }
                signedIn = account
                val folderBrowser = connector.folderBrowser(account.id)
                browser = folderBrowser
                _state.update {
                    it.copy(
                        step = AddDriveStep.ChooseFolder(account, browsable = folderBrowser != null),
                        // Without a browser (Proton) the typed name is the synced folder itself.
                        createRootFolder = folderBrowser != null
                    )
                }
                if (folderBrowser != null) open("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(step = AddDriveStep.ChooseProvider, error = "Sign-in failed: ${describe(e)}")
                }
            }
        }
    }

    fun open(path: String) {
        val step = _state.value.step as? AddDriveStep.ChooseFolder ?: return
        val browser = browser ?: return
        _state.update { it.copy(step = step.copy(path = path, folders = null), error = null) }
        viewModelScope.launch {
            val result = try {
                Result.success(browser.listFolders(path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.update { s ->
                val current = s.step as? AddDriveStep.ChooseFolder
                if (current?.path != path) return@update s
                s.copy(
                    step = current.copy(folders = result.getOrDefault(emptyList())),
                    error = result.exceptionOrNull()?.let { "Could not list folders: ${describe(it)}" }
                )
            }
        }
    }

    fun setCreateRootFolder(value: Boolean) = _state.update { it.copy(createRootFolder = value) }

    fun setRootFolderName(value: String) = _state.update { it.copy(rootFolderName = value) }

    fun connect() {
        val state = _state.value
        val step = state.step as? AddDriveStep.ChooseFolder ?: return
        val name = state.rootFolderName.trim()
        val account = DriveAccount(
            id = step.account.id,
            provider = step.account.provider,
            label = step.account.label,
            remoteFolder = if (step.browsable) step.path else name,
            createRootFolder = step.browsable && state.createRootFolder,
            rootFolderName = name.ifEmpty { DriveAccount.DEFAULT_ROOT_FOLDER }
        )
        syncManager.addDrive(account)
        signedIn = null
        _state.update { it.copy(done = true) }
    }

    /** Goes up one folder or back to the provider list. Returns false when the screen should be left. */
    fun back(): Boolean {
        val step = _state.value.step
        return when {
            step is AddDriveStep.ChooseFolder && step.browsable && step.path.isNotEmpty() -> {
                open(step.path.substringBeforeLast('/', ""))
                true
            }

            step is AddDriveStep.ChooseFolder -> {
                discard()
                _state.update { it.copy(step = AddDriveStep.ChooseProvider, error = null) }
                true
            }

            else -> false
        }
    }

    private fun discard() {
        signedIn?.let(syncManager::discardSignIn)
        signedIn = null
        browser = null
    }

    override fun onCleared() {
        discard()
        super.onCleared()
    }

    private fun describe(e: Throwable) = e.message ?: e.javaClass.simpleName

    companion object {
        /** Whether [name] can be used as a single folder name. */
        fun isValidFolderName(name: String): Boolean {
            val trimmed = name.trim()
            return trimmed.isNotEmpty() && !trimmed.startsWith(".") && trimmed.none { it == '/' || it == '\\' }
        }
    }
}
