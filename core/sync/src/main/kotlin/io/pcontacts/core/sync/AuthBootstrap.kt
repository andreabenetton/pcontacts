// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync

import android.content.Context
import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.storage.EncryptedSecretStore
import io.pcontacts.core.storage.SecretStore
import io.pcontacts.core.sync.auth.SrpLoginOrchestrator

/**
 * Single composition root for the login flow. `:app`'s LoginActivity
 * calls `createLoginOrchestrator(applicationContext)` and hands the
 * result to the LoginViewModel. Keeping the wiring here means
 * :feature:onboarding never has to know about ProtonApiFactory,
 * EncryptedSecretStore, or SrpClient — and the boundary rule from
 * ADR-0011 ("feature modules reach :core:crypto / :core:proton-api only
 * through :core:sync") stays mechanically true at the import level.
 *
 * The returned orchestrator gets a fresh `InMemorySession`; tokens
 * land in the persistent `EncryptedSecretStore` on success. A bridge
 * that promotes the SecretStore-held tokens into an app-wide Session
 * for subsequent authenticated API calls (refresh, sync) ships when
 * the sync engine needs it.
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
}
