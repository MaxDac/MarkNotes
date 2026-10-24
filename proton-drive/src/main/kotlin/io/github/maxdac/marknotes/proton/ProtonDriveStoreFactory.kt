package io.github.maxdac.marknotes.proton

import android.content.Context
import android.util.Log
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.proton.core.crypto.android.pgp.GOpenPGPCrypto
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.LoggerProvider
import me.proton.drive.sdk.ProtonDriveSdk
import me.proton.drive.sdk.entity.ClientCreateRequest

/**
 * Creates the SDK-backed [RemoteDriveStore]: wires the Drive SDK to a protoncore network stack that
 * uses the app's Proton session, and to address-key resolvers that unlock keys with the passphrases
 * derived at login.
 */
class ProtonDriveStoreFactory(
    context: Context,
    baseUrl: String = ProtonAuthClient.DEFAULT_BASE_URL,
    private val appVersion: String = ProtonAuthClient.APP_VERSION,
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) : RemoteDriveStoreFactory {
    private val context = context.applicationContext

    // Both the SDK and Retrofit resolve relative paths, so the base URL must end with '/'.
    private val baseUrl = baseUrl.trimEnd('/') + "/"

    override suspend fun create(sessions: ProtonSessionSource, remoteFolderName: String): RemoteDriveStore {
        val session = sessions.current ?: throw RemoteUnavailableException("Not signed in to Proton.")
        val userId = UserId(session.userIdOrUid)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val bridge = ProtonSessionBridge(sessions, diagnostics)
            val apiProvider = createApiProvider(context, baseUrl, appVersion, bridge, scope)
            val keys = ProtonAddressKeys(apiProvider, sessions, userId, GOpenPGPCrypto())
            val client = ProtonDriveSdk.protonDriveClientCreate(
                coroutineScope = scope,
                userId = userId,
                apiProvider = apiProvider,
                request = ClientCreateRequest(
                    baseUrl = baseUrl,
                    loggerProvider = ProtonDriveSdk.loggerProvider { level, _, _ -> log(level) },
                    // SQLite database for the SDK's node/metadata cache.
                    cachePath = cacheDir(context).apply { mkdirs() }.resolve("cache.sqlite").absolutePath
                ),
                userAddressResolver = keys,
                publicAddressResolver = keys
            )
            return ProtonDriveStore(client, remoteFolderName, onClose = { scope.cancel() })
        } catch (e: CancellationException) {
            scope.cancel()
            throw e
        } catch (e: RemoteUnavailableException) {
            scope.cancel()
            throw e
        } catch (e: LinkageError) {
            scope.cancel()
            throw RemoteUnavailableException("The Proton Drive SDK native library isn't available on this device.", e)
        } catch (e: Exception) {
            scope.cancel()
            throw RemoteUnavailableException("Couldn't start the Proton Drive SDK: ${e.message}", e)
        }
    }

    private fun log(level: LoggerProvider.Level) {
        val line = sdkLogLine(level, diagnostics) ?: return
        Log.println(line.priority, TAG, line.text)
    }

    companion object {
        private const val TAG = "ProtonDriveSdk"
        private const val CACHE_DIR = "proton-drive"

        /**
         * The SDK's node/metadata cache (`cache.sqlite` with its `-wal`/`-shm` files). It belongs to the
         * signed-in session: delete it on sign-out, once the stores using it are closed.
         */
        fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR)
    }
}
