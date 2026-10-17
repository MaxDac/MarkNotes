package io.github.maxdac.marknotes.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.core.auth.LoginResult
import io.github.maxdac.marknotes.core.auth.ProtonApiException
import io.github.maxdac.marknotes.core.auth.ProtonSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LoginState(
    val busy: Boolean = false,
    val needsTwoFactor: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    private var pendingSession: ProtonSession? = null
    private var password: String = ""

    fun login(username: String, password: String) = launchAction {
        this.password = password
        when (val result = container.authClient.login(username.trim(), password)) {
            is LoginResult.Success -> complete(result.session)
            is LoginResult.TwoFactorRequired -> {
                pendingSession = result.session
                _state.value = _state.value.copy(needsTwoFactor = true)
            }
        }
    }

    fun submitCode(code: String) = launchAction {
        val session = pendingSession ?: error("No pending login")
        complete(container.authClient.submitTwoFactor(session, code))
    }

    private suspend fun complete(session: ProtonSession) {
        // Key passphrases are needed by the Drive SDK for decryption; failing here is not fatal for login.
        val passphrases = try {
            container.authClient.fetchKeyPassphrases(session, password)
        } catch (e: Exception) {
            emptyMap<String, String>()
        }
        password = ""
        pendingSession = null
        container.syncManager.onLoggedIn(session.copy(keyPassphrases = passphrases))
        _state.value = _state.value.copy(done = true)
        container.syncManager.syncNow()
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                block()
            } catch (e: ProtonApiException) {
                _state.value = _state.value.copy(error = e.userMessage)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Login failed")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    override fun onCleared() {
        password = ""
        super.onCleared()
    }
}
