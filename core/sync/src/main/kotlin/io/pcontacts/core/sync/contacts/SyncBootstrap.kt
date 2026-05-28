// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.content.ContentProviderClient
import android.content.Context
import io.pcontacts.core.contactswriter.BatchApplier
import io.pcontacts.core.contactswriter.DirtyContactReader
import io.pcontacts.core.contactswriter.DirtyFlagClearer
import io.pcontacts.core.contactswriter.LocalGroupsWriter
import io.pcontacts.core.contactswriter.RawContactDataReader
import io.pcontacts.core.contactswriter.RawContactReader
import io.pcontacts.core.crypto.openpgp.BouncyCastleKeyUnlock
import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.crypto.openpgp.KeyUnlockException
import io.pcontacts.core.crypto.openpgp.UnlockedKey
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.contacts.ContactEmailsPager
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.storage.EncryptedSecretStore
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.storage.db.PcontactsDatabase
import io.pcontacts.core.sync.auth.SecretStoreHumanVerificationSource
import io.pcontacts.core.sync.contacts.decrypt.ContactDecryptBootstrap
import io.pcontacts.core.sync.contacts.decrypt.DecryptUnavailableException
import io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp
import io.pcontacts.core.sync.contacts.encrypt.ContactEncryptBootstrap
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
 * Three factory methods are exposed:
 *   - `createEmailSyncEngine` — name + email only, no decrypt; kept for
 *     a future "fast-sync" mode or as a fallback when the decrypt
 *     prerequisites (keyPassword + primary key) are unavailable.
 *   - `createContactDetailSyncEngine` — full fetch + decrypt + merge
 *     (plan §17 task 17 wired end-to-end). The current read-only path.
 *     Throws `DecryptUnavailableException` if keyPassword / primary key
 *     are missing — re-login required.
 *   - `createBidirectionalEngines` — unlocks the PGP key ring once
 *     and returns both a [ContactWriteEngine] (push) and a
 *     [ContactDetailSyncEngine] (pull) sharing the same session and
 *     key material (ADR-0017 §7B).
 */
object SyncBootstrap {

    suspend fun countVerificationStats(context: Context): Pair<Int, Int> {
        val db = DatabaseFactory.create(context.applicationContext)
        val dao = db.contactMapDao()
        return dao.countLive() to dao.countUnverified()
    }

    suspend fun loadLauncherStatus(context: Context): LauncherStatus {
        val db = DatabaseFactory.create(context.applicationContext)
        val contactDao = db.contactMapDao()
        val outboxDao = db.outboxDao()
        return LauncherStatus(
            totalContacts = contactDao.countLive(),
            unverifiedContacts = contactDao.countUnverified(),
            lastSyncedAtMillis = contactDao.maxLastSyncedAt(),
            pendingChanges = outboxDao.countPending(),
            quarantinedChanges = outboxDao.countQuarantined()
        )
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
        val api = ProtonApiFactory(
            config = ProtonApiConfig(),
            session = session,
            humanVerificationTokens = SecretStoreHumanVerificationSource(secretStore)
        )
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
            refreshConfig = refreshConfig,
            humanVerificationTokens = SecretStoreHumanVerificationSource(secretStore)
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

    /**
     * Unlocks the PGP key ring once and returns both a write engine
     * (push) and a read engine (pull) sharing the same session, key
     * material, and database instance. The SyncAdapter calls
     * `writeEngine.push()` **before** `readEngine.sync()` per
     * ADR-0017 §7B (push-before-pull).
     *
     * Throws [DecryptUnavailableException] for the same three failure
     * modes as [createContactDetailSyncEngine].
     */
    suspend fun createBidirectionalEngines(
        context: Context,
        provider: ContentProviderClient,
        logger: Logger = RedactingLogger(tag = "ContactWrite", sink = NoOpSink)
    ): Pair<ContactWriteEngine, ContactDetailSyncEngine> {
        val appContext = context.applicationContext
        val secretStore = EncryptedSecretStore.create(appContext)
        val session = InMemorySession().apply {
            val uid = secretStore.uid()
            val token = secretStore.accessToken()
            if (uid != null && token != null) update(uid = uid, accessToken = token)
        }
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
            refreshConfig = refreshConfig,
            humanVerificationTokens = SecretStoreHumanVerificationSource(secretStore)
        )
        val openPgp = BouncyCastleOpenPgpService()
        val unlocked = unlockPrimaryKey(secretStore, apis)

        val cardCryptoOp = OpenPgpCardCryptoOp.build(
            openPgp = openPgp,
            decryptionKeys = unlocked.allPrivateKeys,
            verificationKeys = listOf(unlocked.public)
        )
        val processor = ContactProcessor(ContactDecrypter(cardCryptoOp))
        val serializer = ContactEncryptBootstrap.createSerializer(openPgp, unlocked)

        val metadataPager = ContactsMetadataPager(api = apis.contacts)
        val db = DatabaseFactory.create(appContext)
        val reader = RawContactReader(provider)
        val applier = BatchApplier(provider)
        val groupsWriter = LocalGroupsWriter(provider)

        val readEngine = ContactDetailSyncEngine(
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

        val writeEngine = buildWriteEngine(apis, db, provider, processor, serializer, logger)
        return writeEngine to readEngine
    }

    private suspend fun unlockPrimaryKey(
        secretStore: EncryptedSecretStore,
        apis: ProtonApiFactory
    ): UnlockedKey {
        val keyPasswordBytes = secretStore.keyPassword()
            ?: throw DecryptUnavailableException("KEY_PASSWORD_MISSING")
        val primary = apis.users.getUser().user.keys
            .firstOrNull { it.primary == 1 && it.active == 1 }
            ?: throw DecryptUnavailableException("NO_PRIMARY_KEY")
        return unlockKey(primary.privateKey, keyPasswordBytes)
    }

    private fun unlockKey(armoredKey: String, keyPasswordBytes: ByteArray): UnlockedKey {
        val passphrase = String(keyPasswordBytes, Charsets.UTF_8).toCharArray()
        return try {
            BouncyCastleKeyUnlock.unlock(armoredKey, passphrase)
        } catch (kue: KeyUnlockException) {
            throw DecryptUnavailableException("KEY_UNLOCK_FAILED", kue)
        } finally {
            passphrase.fill(' ')
            keyPasswordBytes.fill(0)
        }
    }

    private fun buildWriteEngine(
        apis: ProtonApiFactory,
        db: PcontactsDatabase,
        provider: ContentProviderClient,
        processor: ContactProcessor,
        serializer: ContactSerializer,
        logger: Logger
    ): ContactWriteEngine {
        val dirtyReader = DirtyContactReader(provider)
        val dataReader = RawContactDataReader(provider)
        val dirtyClearer = DirtyFlagClearer(provider)
        return ContactWriteEngine(
            contactsApi = apis.contacts,
            serializer = serializer,
            outboxDao = db.outboxDao(),
            contactMapDao = db.contactMapDao(),
            logger = logger,
            readLocalContact = { protonContactId ->
                val mapping = db.contactMapDao().findByProtonId(protonContactId)
                if (mapping != null) {
                    val row = withContext(Dispatchers.IO) {
                        dataReader.read(mapping.androidRawContactId, protonContactId)
                    }
                    row?.let { RowToDecryptedContact.convert(it, protonContactId, mapping.protonUid) }
                } else null
            },
            readDirtyContacts = { account ->
                withContext(Dispatchers.IO) { dirtyReader.readDirty(account) }
            },
            readContactRow = { rawContactId, sourceId ->
                withContext(Dispatchers.IO) { dataReader.read(rawContactId, sourceId) }
            },
            clearDirtyFlag = { account, rawContactId ->
                withContext(Dispatchers.IO) { dirtyClearer.clearDirty(account, rawContactId) }
            },
            fetchServerContact = { protonContactId ->
                try {
                    val response = apis.contacts.getContact(protonContactId)
                    processor.process(response.contact)
                } catch (_: Exception) {
                    null
                }
            }
        )
    }
}
