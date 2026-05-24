// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.content.ContentProviderClient
import android.content.Context
import io.pcontacts.core.contactswriter.BatchApplier
import io.pcontacts.core.contactswriter.LocalGroupsWriter
import io.pcontacts.core.contactswriter.RawContactReader
import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.contacts.ContactEmailsPager
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.storage.EncryptedSecretStore
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.sync.contacts.decrypt.ContactDecryptBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single composition root for the sync path. The SyncAdapter in :app
 * provides a `ContentProviderClient` (its only privileged resource)
 * and gets back a fully wired engine. Mirrors `AuthBootstrap` for the
 * login flow.
 *
 * Session is hydrated from the persisted SecretStore tokens: post-login,
 * `secretStore.uid()` + `secretStore.accessToken()` are populated; the
 * OkHttp interceptor stack then attaches `x-pm-uid` + `Authorization`
 * to every contacts request automatically. If either is missing the
 * engine's sync() call will fail at the first network hop with a
 * non-sensitive error (401) — which is the desired outcome ("re-login
 * required") rather than a silent zero-contact sync.
 *
 * Two engines are exposed:
 *   - `createEmailSyncEngine` — name + email only, no decrypt; kept for
 *     a future "fast-sync" mode or as a fallback when the decrypt
 *     prerequisites (keyPassword + primary key) are unavailable.
 *   - `createContactDetailSyncEngine` — full fetch + decrypt + merge
 *     (plan §17 task 17 wired end-to-end). The current production path.
 *     Throws `DecryptUnavailableException` if keyPassword / primary key
 *     are missing — re-login required.
 */
object SyncBootstrap {

    suspend fun countVerificationStats(context: Context): Pair<Int, Int> {
        val db = DatabaseFactory.create(context.applicationContext)
        val dao = db.contactMapDao()
        return dao.countLive() to dao.countUnverified()
    }

    fun createEmailSyncEngine(
        context: Context,
        provider: ContentProviderClient
    ): EmailSyncEngine {
        val appContext = context.applicationContext
        val secretStore = EncryptedSecretStore.create(appContext)
        val session = InMemorySession().apply {
            val uid = secretStore.uid()
            val token = secretStore.accessToken()
            if (uid != null && token != null) update(uid = uid, accessToken = token)
        }
        val api = ProtonApiFactory(config = ProtonApiConfig(), session = session)
        val pager = ContactEmailsPager(api = api.contacts)
        val db = DatabaseFactory.create(appContext)
        val reader = RawContactReader(provider)
        val applier = BatchApplier(provider)
        return EmailSyncEngine(
            pager = pager,
            contactMapDao = db.contactMapDao(),
            // The ContactsContract calls under reader/applier are blocking;
            // park them on Dispatchers.IO so the engine's suspend chain
            // doesn't pin the calling thread.
            readExisting = { account -> withContext(Dispatchers.IO) { reader.readExisting(account) } },
            applyIntents = { account, intents -> withContext(Dispatchers.IO) { applier.apply(account, intents) } }
        )
    }

    /**
     * Suspend because `ContactDecryptBootstrap.createProcessor` makes a
     * blocking network call (`/users`) to fetch the primary armored key
     * before unlocking. The SyncAdapter wraps this in `runBlocking`.
     */
    suspend fun createContactDetailSyncEngine(
        context: Context,
        provider: ContentProviderClient
    ): ContactDetailSyncEngine {
        val appContext = context.applicationContext
        val secretStore = EncryptedSecretStore.create(appContext)
        val session = InMemorySession().apply {
            val uid = secretStore.uid()
            val token = secretStore.accessToken()
            if (uid != null && token != null) update(uid = uid, accessToken = token)
        }
        // 401 → /auth/refresh → retry wiring. Single-flight inside
        // TokenRefresher; rotated tokens persist back into SecretStore.
        val refreshConfig = ProtonApiFactory.RefreshConfig(
            mutableSession = session,
            getRefreshToken = { secretStore.refreshToken() },
            onTokensRefreshed = { accessToken, refreshToken ->
                secretStore.setAccessToken(accessToken)
                secretStore.setRefreshToken(refreshToken)
            }
        )
        val apis = ProtonApiFactory(
            config = ProtonApiConfig(),
            session = session,
            refreshConfig = refreshConfig
        )
        val openPgp = BouncyCastleOpenPgpService()
        val processor = ContactDecryptBootstrap.createProcessor(
            secretStore = secretStore,
            usersApi = apis.users,
            openPgp = openPgp
        )

        val metadataPager = ContactsMetadataPager(api = apis.contacts)
        val db = DatabaseFactory.create(appContext)
        val reader = RawContactReader(provider)
        val applier = BatchApplier(provider)
        val groupsWriter = LocalGroupsWriter(provider)
        return ContactDetailSyncEngine(
            metadataPager = metadataPager,
            contactsApi = apis.contacts,
            labelsApi = apis.labels,
            processor = processor,
            contactMapDao = db.contactMapDao(),
            readExisting = { account -> withContext(Dispatchers.IO) { reader.readExisting(account) } },
            applyIntents = { account, intents -> withContext(Dispatchers.IO) { applier.apply(account, intents) } },
            reconcileGroups = { account, labels ->
                withContext(Dispatchers.IO) { groupsWriter.reconcile(account, labels) }
            }
        )
    }
}
