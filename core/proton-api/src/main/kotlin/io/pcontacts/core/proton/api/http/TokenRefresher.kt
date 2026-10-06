// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.auth.RefreshRequest
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
        } catch (e: HumanVerificationRequiredException) {
            // 9001 on /auth/refresh — propagate so OkHttp surfaces it to the
            // caller (the original Call.execute()), not demote to a generic
            // refresh-failed silent retry that would loop or trigger logout.
            logger.warn { "auth/refresh returned 9001 — human verification required" }
            throw e
        } catch (e: HttpException) {
            // [V] WebClients refreshHandlers.ts: 400/401/422 rule the session invalid (revoked on
            // the web, password or password mode changed); 409/429/5xx mean retry later.
            if (e.code() in SESSION_INVALID_REFRESH_STATUSES) {
                logger.warn { "auth/refresh refused with HTTP ${e.code()} — session revoked" }
                throw SessionRevokedException(e.code(), e)
            }
            logger.error(e) { "auth/refresh call failed" }
            return@withLock false
        } catch (t: Throwable) {
            logger.error(t) { "auth/refresh call failed" }
            return@withLock false
        }
        mutableSession.update(uid = response.uid, accessToken = response.accessToken)
        onTokensRefreshed(response.accessToken, response.refreshToken)
        true
    }
}

/**
 * Proton refused `/auth/refresh` with a status that rules the session invalid: it was revoked
 * (signed out of all devices, password or password mode changed on the web). Only a new
 * sign-in helps; the sync asks for it instead of retrying. An [IOException] so it leaves the
 * OkHttp authenticator the way [HumanVerificationRequiredException] does.
 */
class SessionRevokedException(val httpStatus: Int, cause: Throwable? = null) :
    IOException("Proton revoked the session (auth/refresh HTTP $httpStatus)", cause)

private val SESSION_INVALID_REFRESH_STATUSES = setOf(400, 401, 422)
