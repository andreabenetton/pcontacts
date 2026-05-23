// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.auth.RefreshRequest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking

/**
 * Drives the `/auth/refresh` half of the 401 → refresh → replay
 * flow. Single-flight: concurrent callers that observe the same
 * stale access token block on a lock; the first one through fires
 * the actual refresh, the rest return success once they see the
 * session's token has rotated.
 *
 * The /auth/refresh call goes through a `refreshOnlyAuthApi` that
 * is NOT wired through `RefreshingAuthenticator` — that's how we
 * avoid recursion (a 401 on the refresh call itself would
 * otherwise re-enter the authenticator infinitely).
 *
 * `onTokensRefreshed` is the persistence hook the caller (typically
 * SecretStore) wires. The mutable session is updated synchronously
 * by the refresher itself so the authenticator can read the new
 * access token immediately.
 */
class TokenRefresher(
    private val refreshOnlyAuthApi: ProtonAuthApi,
    private val mutableSession: InMemorySession,
    private val getRefreshToken: () -> String?,
    private val onTokensRefreshed: (accessToken: String, refreshToken: String) -> Unit,
    private val logger: Logger = RedactingLogger(tag = "TokenRefresh", sink = NoOpSink)
) {
    private val lock = ReentrantLock()

    /**
     * Refreshes the session if the access token the caller saw is
     * still the one the session is holding. Returns true when a
     * usable fresh token is available afterwards.
     *
     * @param tokenObservedDuring401 the bearer value present on the
     *        request that got the 401 — null if the request was
     *        already unauthenticated (unusual).
     */
    fun refreshIfStillStale(tokenObservedDuring401: String?): Boolean = lock.withLock {
        // Single-flight: if someone else already rotated the session
        // token while we were waiting on the lock, use their result.
        val nowToken = mutableSession.accessToken()
        if (nowToken != null && nowToken != tokenObservedDuring401) {
            return@withLock true
        }
        val refreshToken = getRefreshToken() ?: return@withLock false
        val response = try {
            runBlocking { refreshOnlyAuthApi.refresh(RefreshRequest(refreshToken = refreshToken)) }
        } catch (t: Throwable) {
            logger.error(t) { "auth/refresh call failed" }
            return@withLock false
        }
        mutableSession.update(uid = response.uid, accessToken = response.accessToken)
        onTokensRefreshed(response.accessToken, response.refreshToken)
        true
    }
}
