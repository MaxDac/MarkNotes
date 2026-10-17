package io.github.maxdac.marknotes.proton

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiManagerFactory
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.NetworkPrefsImpl
import me.proton.core.network.data.ProtonCookieStore
import me.proton.core.network.data.client.ClientVersionValidatorImpl
import me.proton.core.network.data.cookie.MemoryCookieStorage
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiClient
import me.proton.core.network.domain.NetworkManager
import me.proton.core.network.domain.NetworkStatus
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.client.ClientIdProvider
import me.proton.core.network.domain.deviceverification.DeviceVerificationListener
import me.proton.core.network.domain.deviceverification.DeviceVerificationMethods
import me.proton.core.network.domain.deviceverification.DeviceVerificationProvider
import me.proton.core.network.domain.feature.FeatureDisabledListener
import me.proton.core.network.domain.humanverification.HumanVerificationAvailableMethods
import me.proton.core.network.domain.humanverification.HumanVerificationDetails
import me.proton.core.network.domain.humanverification.HumanVerificationListener
import me.proton.core.network.domain.humanverification.HumanVerificationProvider
import me.proton.core.network.domain.scopes.MissingScopeListener
import me.proton.core.network.domain.scopes.MissingScopeResult
import me.proton.core.network.domain.scopes.MissingScopeState
import me.proton.core.network.domain.scopes.Scope
import me.proton.core.network.domain.server.ServerTimeListener
import me.proton.core.network.domain.session.SessionId
import me.proton.core.util.kotlin.DefaultDispatcherProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.GET
import retrofit2.http.Query

/** Proton core key endpoints, called through protoncore so they share auth and token refresh with the SDK. */
internal interface KeysApi : BaseRetrofitApi {
    @GET("core/v4/users")
    suspend fun user(): JsonObject

    @GET("core/v4/addresses")
    suspend fun addresses(): JsonObject

    @GET("core/v4/keys/all")
    suspend fun publicKeys(@Query("Email") email: String, @Query("InternalOnly") internalOnly: Int): JsonObject
}

/**
 * Builds the protoncore [ApiProvider] the Drive SDK sends its HTTP requests through. Interactive flows
 * (human/device verification, missing scopes) are not supported: those requests just fail, and the
 * user signs in again from the app.
 */
internal fun createApiProvider(
    context: Context,
    baseUrl: String,
    appVersion: String,
    bridge: ProtonSessionBridge,
    scope: CoroutineScope
): ApiProvider {
    val factory = ApiManagerFactory(
        baseUrl = baseUrl.toHttpUrl(),
        apiClient = MarkNotesApiClient(appVersion),
        clientIdProvider = SessionClientIdProvider,
        serverTimeListener = NoServerTimeListener,
        networkManager = AndroidNetworkManager(context),
        prefs = NetworkPrefsImpl(context),
        sessionProvider = bridge,
        sessionListener = bridge,
        humanVerificationProvider = NoHumanVerification,
        humanVerificationListener = NoHumanVerification,
        deviceVerificationProvider = NoDeviceVerification,
        deviceVerificationListener = NoDeviceVerification,
        missingScopeListener = NoMissingScopes,
        featureDisabledListener = NoFeatureDisabledListener,
        cookieStore = ProtonCookieStore(MemoryCookieStorage(), MemoryCookieStorage()),
        scope = scope,
        clientVersionValidator = ClientVersionValidatorImpl(),
        dohAlternativesListener = null,
        okHttpClient = OkHttpClient(),
        interceptors = emptySet()
    )
    return ApiProvider(factory, bridge, DefaultDispatcherProvider())
}

private class MarkNotesApiClient(override val appVersionHeader: String) : ApiClient {
    override val userAgent: String = "MarkNotes/${appVersionHeader.substringAfter('@')} (Android)"
    override val enableDebugLogging: Boolean = false

    // Plain routing only: the app's own login client talks to the API directly too.
    override suspend fun shouldUseDoh(): Boolean = false

    override fun forceUpdate(errorMessage: String) = Unit
}

private object SessionClientIdProvider : ClientIdProvider {
    override suspend fun getClientId(sessionId: SessionId?): ClientId? = sessionId?.let { ClientId.AccountSession(it) }
}

private object NoServerTimeListener : ServerTimeListener {
    override fun onServerTimeMillisUpdated(epochMillis: Long) = Unit
}

private object NoHumanVerification : HumanVerificationProvider, HumanVerificationListener {
    override suspend fun getHumanVerificationDetails(clientId: ClientId): HumanVerificationDetails? = null

    override suspend fun onHumanVerificationNeeded(
        clientId: ClientId,
        methods: HumanVerificationAvailableMethods
    ): HumanVerificationListener.HumanVerificationResult = HumanVerificationListener.HumanVerificationResult.Failure

    override suspend fun onHumanVerificationInvalid(clientId: ClientId) = Unit
}

private object NoDeviceVerification : DeviceVerificationProvider, DeviceVerificationListener {
    override suspend fun getSolvedChallenge(sessionId: SessionId?): String? = null

    override suspend fun getSolvedChallenge(challengePayload: String): String? = null

    override suspend fun setSolvedChallenge(sessionId: SessionId, challengePayload: String, solved: String) = Unit

    override suspend fun onDeviceVerification(
        sessionId: SessionId,
        methods: DeviceVerificationMethods
    ): DeviceVerificationListener.DeviceVerificationResult = DeviceVerificationListener.DeviceVerificationResult.Failure
}

private object NoMissingScopes : MissingScopeListener {
    override val state: SharedFlow<MissingScopeState> = MutableSharedFlow()

    override suspend fun onMissingScope(userId: UserId, scopes: List<Scope>): MissingScopeResult =
        MissingScopeResult.Failure

    override suspend fun onMissingScopeSuccess() = Unit

    override suspend fun onMissingScopeFailure() = Unit
}

private object NoFeatureDisabledListener : FeatureDisabledListener {
    override suspend fun onFeatureDisabled(sessionId: SessionId?) = Unit
}

private class AndroidNetworkManager(context: Context) : NetworkManager() {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            notifyObservers(networkStatus)
        }

        override fun onLost(network: Network) {
            notifyObservers(networkStatus)
        }
    }

    override val networkStatus: NetworkStatus
        get() {
            val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            return when {
                capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                    NetworkStatus.Disconnected

                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkStatus.Unmetered

                else -> NetworkStatus.Metered
            }
        }

    override fun register() {
        connectivity?.registerDefaultNetworkCallback(callback)
    }

    override fun unregister() {
        connectivity?.unregisterNetworkCallback(callback)
    }
}
