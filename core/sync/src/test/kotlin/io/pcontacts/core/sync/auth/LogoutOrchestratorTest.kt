// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import android.accounts.Account
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.AuthRequest
import io.pcontacts.core.proton.api.auth.AuthResponse
import io.pcontacts.core.proton.api.auth.InfoRequest
import io.pcontacts.core.proton.api.auth.InfoResponse
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.auth.RefreshRequest
import io.pcontacts.core.proton.api.auth.RefreshResponse
import io.pcontacts.core.proton.api.auth.TwoFactorRequest
import io.pcontacts.core.proton.api.auth.TwoFactorResponse
import io.pcontacts.core.storage.InMemorySecretStore
import io.pcontacts.core.storage.InMemoryUserPreferences
import io.pcontacts.core.storage.SecretStore
import io.pcontacts.core.storage.SecretStoreWriteException
import io.pcontacts.core.storage.db.dao.SyncStateDao
import io.pcontacts.core.storage.db.entity.SyncStateEntity
import io.pcontacts.core.sync.contacts.WriteFakeContactMapDao
import io.pcontacts.core.sync.contacts.WriteFakeOutboxDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LogoutOrchestratorTest {

    // The mockable android.jar no-ops the constructor, and the orchestrator reads `name`.
    private val account = Account("user@proton.me", "io.pcontacts.account").also {
        Account::class.java.getField("name").apply { isAccessible = true }.set(it, "user@proton.me")
    }
    private val session = InMemorySession(uid = "uid-1", accessToken = "tok")
    private val prefs = InMemoryUserPreferences().apply {
        lastSyncSuccessAtMillis = 42L
        lastSyncErrorCode = "io"
        syncProgressTotal = 9
        syncIntervalHours = 6L
    }
    private var accountRemovals = 0

    private class FakeAuthApi(private val revokeFails: Boolean = false) : ProtonAuthApi {
        var revoked = 0
        override suspend fun getInfo(request: InfoRequest): InfoResponse = error("unused")
        override suspend fun auth(request: AuthRequest): AuthResponse = error("unused")
        override suspend fun auth2FA(request: TwoFactorRequest): TwoFactorResponse = error("unused")
        override suspend fun refresh(request: RefreshRequest): RefreshResponse = error("unused")
        override suspend fun revoke() {
            if (revokeFails) throw IOException("offline")
            revoked++
        }
    }

    private class FakeSyncStateDao : SyncStateDao {
        val deleted = ArrayList<String>()
        override suspend fun upsert(state: SyncStateEntity) = Unit
        override suspend fun get(name: String): SyncStateEntity? = null
        override suspend fun delete(name: String) { deleted += name }
    }

    private class ThrowingSecretStore : SecretStore by InMemorySecretStore() {
        override fun logout() = throw SecretStoreWriteException("logout")
    }

    private fun orchestrator(
        authApi: FakeAuthApi = FakeAuthApi(),
        secretStore: SecretStore = InMemorySecretStore().apply { setUid("uid-1") }
    ) = LogoutOrchestrator(
        authApi = authApi,
        secretStore = secretStore,
        session = session,
        contactMapDao = WriteFakeContactMapDao(),
        outboxDao = WriteFakeOutboxDao(),
        syncStateDao = FakeSyncStateDao(),
        userPreferences = prefs,
        deleteAllContactsFor = { 3 },
        removeAndroidAccount = {
            accountRemovals++
            true
        }
    )

    @Test fun happy_path_runs_every_step_and_is_successful() = runTest {
        val api = FakeAuthApi()
        val store = InMemorySecretStore().apply { setUid("uid-1") }

        val result = orchestrator(api, store).logout(account)

        assertTrue(result.successful)
        assertEquals(3, result.contactsDeleted)
        assertTrue(result.androidAccountRemoved)
        assertEquals(1, api.revoked)
        assertNull(store.uid())
        assertNull(session.uid())
        assertEquals(1, accountRemovals)
    }

    @Test fun revoke_failure_is_non_fatal_and_still_wipes_and_removes_the_account() = runTest {
        val store = InMemorySecretStore().apply { setUid("uid-1") }

        val result = orchestrator(FakeAuthApi(revokeFails = true), store).logout(account)

        assertEquals(listOf(LogoutOrchestrator.LOGOUT_ERR_REVOKE), result.errors)
        assertTrue(result.androidAccountRemoved)
        assertNull(store.uid())
        assertEquals(1, accountRemovals)
    }

    @Test fun secret_store_failure_keeps_the_android_account_and_reports_the_error() = runTest {
        val result = orchestrator(secretStore = ThrowingSecretStore()).logout(account)

        assertFalse(result.successful)
        assertEquals(listOf(LogoutOrchestrator.LOGOUT_ERR_SECRETSTORE), result.errors)
        assertFalse(result.androidAccountRemoved)
        assertEquals(0, accountRemovals)
        assertNull(session.uid())
    }

    @Test fun logout_clears_the_accounts_sync_state_but_keeps_device_preferences() = runTest {
        orchestrator().logout(account)

        assertEquals(0L, prefs.lastSyncSuccessAtMillis)
        assertNull(prefs.lastSyncErrorCode)
        assertEquals(0, prefs.syncProgressTotal)
        assertEquals(6L, prefs.syncIntervalHours)
    }
}
