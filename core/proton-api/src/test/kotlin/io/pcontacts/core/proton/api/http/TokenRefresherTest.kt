// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.AuthResponse
import io.pcontacts.core.proton.api.auth.InfoRequest
import io.pcontacts.core.proton.api.auth.InfoResponse
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.auth.RefreshRequest
import io.pcontacts.core.proton.api.auth.RefreshResponse
import io.pcontacts.core.proton.api.auth.TwoFactorRequest
import io.pcontacts.core.proton.api.auth.TwoFactorResponse
import io.pcontacts.core.proton.api.auth.AuthRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRefresherTest {

    @Test fun first_refresh_rotates_session_and_persists_via_callback() {
        val session = InMemorySession(uid = "uid-1", accessToken = "stale-access")
        val persisted = mutableListOf<Pair<String, String>>()
        val refresher = TokenRefresher(
            refreshOnlyAuthApi = SuccessRefreshApi(newAccessToken = "fresh-access", newRefreshToken = "fresh-refresh"),
            mutableSession = session,
            getRefreshToken = { "stored-refresh" },
            onTokensRefreshed = { a, r -> persisted += a to r }
        )

        val ok = refresher.refreshIfStillStale(tokenObservedDuring401 = "stale-access")

        assertTrue(ok)
        assertEquals("fresh-access", session.accessToken())
        assertEquals(listOf("fresh-access" to "fresh-refresh"), persisted)
    }

    @Test fun second_caller_with_a_stale_observation_uses_the_already_refreshed_token() {
        val session = InMemorySession(uid = "uid-1", accessToken = "current-access")
        // After the "first refresh" happened, the session holds current-access.
        // A second 401 with the OLDER observation must NOT trigger another refresh.
        val fake = SuccessRefreshApi(newAccessToken = "should-not-be-issued", newRefreshToken = "x")
        val refresher = TokenRefresher(
            refreshOnlyAuthApi = fake,
            mutableSession = session,
            getRefreshToken = { "stored-refresh" },
            onTokensRefreshed = { _, _ -> }
        )

        val ok = refresher.refreshIfStillStale(tokenObservedDuring401 = "older-stale")

        assertTrue(ok)
        assertEquals(0, fake.callCount)   // single-flight short-circuit
    }

    @Test fun refresh_call_failure_returns_false_and_leaves_session_intact() {
        val session = InMemorySession(uid = "uid-1", accessToken = "stale")
        val refresher = TokenRefresher(
            refreshOnlyAuthApi = ThrowingRefreshApi(),
            mutableSession = session,
            getRefreshToken = { "stored-refresh" },
            onTokensRefreshed = { _, _ -> error("must not persist on failure") }
        )

        val ok = refresher.refreshIfStillStale(tokenObservedDuring401 = "stale")

        assertFalse(ok)
        assertEquals("stale", session.accessToken())
    }

    @Test fun no_stored_refresh_token_returns_false_without_calling_the_api() {
        val session = InMemorySession(uid = "uid-1", accessToken = "stale")
        val fake = SuccessRefreshApi(newAccessToken = "x", newRefreshToken = "y")
        val refresher = TokenRefresher(
            refreshOnlyAuthApi = fake,
            mutableSession = session,
            getRefreshToken = { null },     // user logged out / never had a refresh token
            onTokensRefreshed = { _, _ -> error("must not persist") }
        )

        assertFalse(refresher.refreshIfStillStale(tokenObservedDuring401 = "stale"))
        assertEquals(0, fake.callCount)
    }

    @Test fun concurrent_callers_observing_the_same_stale_token_fire_exactly_one_refresh() {
        val session = InMemorySession(uid = "uid-1", accessToken = "stale")
        // A refresh API that counts invocations AND blocks until released so
        // we can pile concurrent callers behind the lock.
        val gate = CountDownLatch(1)
        val callCount = AtomicInteger(0)
        val gatedApi = object : NoOpAuthApi() {
            override suspend fun refresh(request: RefreshRequest): RefreshResponse {
                callCount.incrementAndGet()
                gate.await(2, TimeUnit.SECONDS)
                return RefreshResponse(
                    accessToken = "fresh", refreshToken = "fresh-r",
                    tokenType = "Bearer", expiresIn = 86400, uid = "uid-1"
                )
            }
        }
        val refresher = TokenRefresher(
            refreshOnlyAuthApi = gatedApi,
            mutableSession = session,
            getRefreshToken = { "stored-refresh" },
            onTokensRefreshed = { _, _ -> }
        )

        val pool = Executors.newFixedThreadPool(4)
        val results = (1..4).map {
            pool.submit<Boolean> { refresher.refreshIfStillStale("stale") }
        }
        // Give the first caller a moment to acquire the lock + enter the await.
        Thread.sleep(100)
        gate.countDown()
        results.forEach { f -> assertTrue(f.get(5, TimeUnit.SECONDS)) }
        pool.shutdown()

        // Exactly one refresh call across 4 concurrent attempts.
        assertEquals(1, callCount.get())
        assertEquals("fresh", session.accessToken())
    }

    // ---- fakes ----

    private class SuccessRefreshApi(
        private val newAccessToken: String,
        private val newRefreshToken: String
    ) : NoOpAuthApi() {
        var callCount = 0
            private set
        override suspend fun refresh(request: RefreshRequest): RefreshResponse {
            callCount += 1
            assertNotNull(request.refreshToken)
            return RefreshResponse(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                tokenType = "Bearer",
                expiresIn = 86400,
                uid = "uid-1"
            )
        }
    }

    private class ThrowingRefreshApi : NoOpAuthApi() {
        override suspend fun refresh(request: RefreshRequest): RefreshResponse {
            throw java.io.IOException("simulated network failure")
        }
    }

    private open class NoOpAuthApi : ProtonAuthApi {
        override suspend fun getInfo(request: InfoRequest): InfoResponse = error("not used")
        override suspend fun auth(request: AuthRequest): AuthResponse = error("not used")
        override suspend fun auth2FA(request: TwoFactorRequest): TwoFactorResponse = error("not used")
        override suspend fun refresh(request: RefreshRequest): RefreshResponse = error("override me")
        override suspend fun revoke() {}
    }
}
