package io.github.maxdac.marknotes.proton

import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.session.SessionListener
import me.proton.core.network.domain.session.SessionProvider

/**
 * Exposes the MarkNotes session to protoncore's network stack. Token refreshes go through
 * [ProtonSessionSource] so the app's stored session stays the single source of truth.
 */
internal class ProtonSessionBridge(private val sessions: ProtonSessionSource) : SessionProvider, SessionListener {
    private val lock = Mutex()

    val userId: UserId?
        get() = sessions.current?.let { UserId(it.userIdOrUid) }

    override suspend fun getSession(sessionId: SessionId?): Session? {
        val session = sessions.current ?: return null
        if (sessionId != null && sessionId.id != session.uid) return null
        return session.toCoreSession()
    }

    override suspend fun getSessions(): List<Session> = listOfNotNull(sessions.current?.toCoreSession())

    override suspend fun getSessionId(userId: UserId?): SessionId? =
        sessions.current?.takeIf { userId == null || it.userIdOrUid == userId.id }?.let { SessionId(it.uid) }

    override suspend fun getUserId(sessionId: SessionId): UserId? =
        sessions.current?.takeIf { it.uid == sessionId.id }?.let { UserId(it.userIdOrUid) }

    override suspend fun <T> withLock(sessionId: SessionId?, action: suspend () -> T): T = lock.withLock { action() }

    override suspend fun requestSession(): Boolean = false

    override suspend fun refreshSession(session: Session): Boolean = try {
        sessions.refresh(session.accessToken) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    override suspend fun onSessionTokenCreated(userId: UserId?, session: Session) = Unit

    override suspend fun onSessionTokenRefreshed(session: Session) = Unit

    override suspend fun onSessionScopesRefreshed(sessionId: SessionId, scopes: List<String>) = Unit

    override suspend fun onSessionForceLogout(session: Session, httpCode: Int) {
        sessions.invalidate()
    }

    private fun ProtonSession.toCoreSession(): Session = Session.Authenticated(
        userId = UserId(userIdOrUid),
        sessionId = SessionId(uid),
        accessToken = accessToken,
        refreshToken = refreshToken,
        scopes = emptyList()
    )
}

/** Older sessions may lack the user ID; the session UID still identifies the single account. */
internal val ProtonSession.userIdOrUid: String get() = userId.ifBlank { uid }
