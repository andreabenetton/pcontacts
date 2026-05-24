// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import io.pcontacts.core.contactswriter.BatchApplier
import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.storage.EncryptedSecretStore
import io.pcontacts.core.storage.SecretStore
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.sync.auth.LogoutOrchestrator
import io.pcontacts.core.sync.auth.SrpLoginOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single composition root for the login + logout flows. `:app`'s
 * LoginActivity calls `createLoginOrchestrator(applicationContext)`
 * and hands the result to the LoginViewModel. The account-removal
 * surface calls `createLogoutOrchestrator(...)` with the live
 * ContentProviderClient + an AccountManager-bound removal callback.
 *
 * Keeping the wiring here means :feature:onboarding never has to
 * know about ProtonApiFactory, EncryptedSecretStore, or SrpClient
 * — and the boundary rule from ADR-0011 ("feature modules reach
 * :core:crypto / :core:proton-api only through :core:sync") stays
 * mechanically true at the import level.
 */
object AuthBootstrap {

    fun createLoginOrchestrator(context: Context): SrpLoginOrchestrator {
        val appContext = context.applicationContext
        val secretStore: SecretStore = EncryptedSecretStore.create(appContext)
        val session = InMemorySession()
        val apis = ProtonApiFactory(
            config = ProtonApiConfig(),
            session = session
        )
        return SrpLoginOrchestrator(
            api = apis.auth,
            usersApi = apis.users,
            srp = SrpClient(),
            secretStore = secretStore,
            session = session
        )
    }

    /**
     * Builds a LogoutOrchestrator. The caller passes a
     * ContentProviderClient (for ContactsContract delete) and an
     * AccountManager-bound removal callback — both are
     * permission-sensitive resources only :app holds.
     *
     * Hydrates the session from persisted tokens so the /auth DELETE
     * carries x-pm-uid + Authorization.
     */
    fun createLogoutOrchestrator(
        context: Context,
        provider: ContentProviderClient,
        removeAndroidAccount: suspend (Account) -> Boolean
    ): LogoutOrchestrator {
        val appContext = context.applicationContext
        val secretStore = EncryptedSecretStore.create(appContext)
        val session = InMemorySession().apply {
            val uid = secretStore.uid()
            val token = secretStore.accessToken()
            if (uid != null && token != null) update(uid = uid, accessToken = token)
        }
        val apis = ProtonApiFactory(config = ProtonApiConfig(), session = session)
        val db = DatabaseFactory.create(appContext)
        val applier = BatchApplier(provider)
        return LogoutOrchestrator(
            authApi = apis.auth,
            secretStore = secretStore,
            session = session,
            contactMapDao = db.contactMapDao(),
            outboxDao = db.outboxDao(),
            syncStateDao = db.syncStateDao(),
            deleteAllContactsFor = { account ->
                withContext(Dispatchers.IO) { applier.deleteAllForAccount(account) }
            },
            removeAndroidAccount = removeAndroidAccount
        )
    }
}
