// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.BouncyCastleKeyUnlock
import io.pcontacts.core.crypto.openpgp.KeyUnlockException
import io.pcontacts.core.crypto.openpgp.OpenPgpService
import io.pcontacts.core.crypto.openpgp.PgpPrivateKeyHandle
import io.pcontacts.core.crypto.openpgp.PgpPublicKeyHandle
import io.pcontacts.core.crypto.openpgp.UnlockedKey
import io.pcontacts.core.crypto.openpgp.VerificationStatus
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.addresses.AddressKeyDto
import io.pcontacts.core.proton.api.addresses.ProtonAddressesApi
import io.pcontacts.core.proton.api.users.ProtonUsersApi
import io.pcontacts.core.proton.api.users.UserKeyDto
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.storage.SecretStore
import kotlinx.coroutines.CancellationException

class DecryptUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Sync-run-scoped key set used by the contact decrypt path. Per
 * ADR-0020, contacts can be encrypted to ANY active user key OR any
 * active address key on the account; the decrypt path therefore
 * unlocks all of them and unions them into one [decryptionKeys] list
 * that [OpenPgpCardCryptoOp] passes to the OpenPGP service.
 *
 *   - `primary` is the user-keys[primary==1, active==1] handle and
 *     is also the recipient that decrypts AddressKey.Token blobs.
 *     The encrypt path (write-back, ADR-0017) uses it as the
 *     self-encryption + signing key.
 *   - `decryptionKeys` is the flattened set of every unlockable
 *     active user-key subkey and every unlockable active address
 *     key (skipped if Token decrypt or unlock failed).
 *   - `verificationKeys` is the matching union of public keys.
 */
data class UnlockedKeySet(
    val primary: UnlockedKey,
    val decryptionKeys: List<PgpPrivateKeyHandle>,
    val verificationKeys: List<PgpPublicKeyHandle>
)

/**
 * Builds a sync-run-scoped [ContactProcessor]. Hydrates the unlocked
 * key set from:
 *   1. `SecretStore.keyPassword()`         (persisted post-SRP)
 *   2. `ProtonUsersApi.getUser()`          (live user keys armored)
 *   3. `ProtonAddressesApi.getAddresses()` (live address keys + Token)
 *   4. `BouncyCastleKeyUnlock.unlock(...)` (passphrase → handles)
 *
 * Throws `DecryptUnavailableException` for the three actionable
 * failure modes the SyncAdapter surfaces to the user:
 *   - `KEY_PASSWORD_MISSING`  → user has not completed login or
 *     keyPassword derivation failed; re-login required.
 *   - `NO_PRIMARY_KEY`        → /users returned no Primary+Active key;
 *     server-side anomaly, surfaced for support.
 *   - `KEY_UNLOCK_FAILED`     → wrong passphrase or corrupted key
 *     material on a user key; almost certainly stale keyPassword
 *     (user changed their Proton password). Re-login required.
 *
 * Address keys that fail to unlock (corrupt Token, missing
 * recipient, future format) are **skipped** and counted in a
 * non-sensitive log line, not raised — the decrypt path falls back
 * to whatever keys did unlock. This matches Proton WebClients'
 * `getDecryptedAddressKeys.ts` policy.
 *
 * The function returns a fresh ContactProcessor each call — its
 * underlying lambda closes over live PGP key material, so callers
 * should let it go out of scope at sync-run end (ADR-0009).
 */
object ContactDecryptBootstrap {

    private const val SUFFIX_LEGACY = " (legacy v1)"

    suspend fun createProcessor(
        secretStore: SecretStore,
        usersApi: ProtonUsersApi,
        addressesApi: ProtonAddressesApi,
        openPgp: OpenPgpService,
        logger: Logger = RedactingLogger(tag = "ContactDecrypt", sink = NoOpSink)
    ): ContactProcessor {
        val keySet = unlockAllKeys(secretStore, usersApi, addressesApi, openPgp, logger)
        val cardCryptoOp = OpenPgpCardCryptoOp.build(
            openPgp = openPgp,
            decryptionKeys = keySet.decryptionKeys,
            verificationKeys = keySet.verificationKeys
        )
        return ContactProcessor(ContactDecrypter(cardCryptoOp))
    }

    /**
     * Fan-out unlock: returns the merged key set the decrypt path
     * needs. Exposed so [io.pcontacts.core.sync.contacts.SyncBootstrap]
     * can share the same fan-out with the bidirectional engine
     * (which also needs the primary handle for the write path's
     * signing + self-encryption).
     */
    suspend fun unlockAllKeys(
        secretStore: SecretStore,
        usersApi: ProtonUsersApi,
        addressesApi: ProtonAddressesApi,
        openPgp: OpenPgpService,
        logger: Logger = RedactingLogger(tag = "ContactDecrypt", sink = NoOpSink)
    ): UnlockedKeySet {
        val keyPasswordBytes = secretStore.keyPassword()
            ?: throw DecryptUnavailableException("KEY_PASSWORD_MISSING")

        try {
            val user = usersApi.getUser().user
            val primaryDto = user.keys.firstOrNull { it.primary == 1 && it.active == 1 }
                ?: throw DecryptUnavailableException("NO_PRIMARY_KEY")

            val primaryUnlocked = unlockUserKey(primaryDto, keyPasswordBytes)
            val nonPrimaryUnlocked = user.keys
                .filter { it.active == 1 && it.id != primaryDto.id }
                .map { unlockUserKey(it, keyPasswordBytes) }
            val allUserUnlocked = listOf(primaryUnlocked) + nonPrimaryUnlocked

            val addresses = fetchAddressesOrEmpty(addressesApi, logger)
            val activeAddressKeys = addresses.flatMap { it.keys }.filter { it.active == 1 }

            val unlockedAddressKeys = mutableListOf<UnlockedKey>()
            var skipped = 0
            for (ak in activeAddressKeys) {
                val result = tryUnlockAddressKey(ak, allUserUnlocked, openPgp, keyPasswordBytes, logger)
                if (result != null) unlockedAddressKeys += result else skipped += 1
            }

            val decryptionKeys = allUserUnlocked.flatMap { it.allPrivateKeys } +
                unlockedAddressKeys.flatMap { it.allPrivateKeys }
            val verificationKeys = allUserUnlocked.map { it.public } +
                unlockedAddressKeys.map { it.public }

            logger.info {
                "unlocked U=${allUserUnlocked.size} user keys + " +
                    "A=${unlockedAddressKeys.size} address keys (skipped=$skipped)"
            }

            return UnlockedKeySet(
                primary = primaryUnlocked,
                decryptionKeys = decryptionKeys,
                verificationKeys = verificationKeys
            )
        } finally {
            keyPasswordBytes.fill(0)
        }
    }

    private fun unlockUserKey(uk: UserKeyDto, keyPasswordBytes: ByteArray): UnlockedKey {
        val passphrase = String(keyPasswordBytes, Charsets.UTF_8).toCharArray()
        return try {
            BouncyCastleKeyUnlock.unlock(uk.privateKey, passphrase)
        } catch (kue: KeyUnlockException) {
            throw DecryptUnavailableException("KEY_UNLOCK_FAILED", kue)
        } finally {
            passphrase.fill(' ')
        }
    }

    private suspend fun fetchAddressesOrEmpty(
        addressesApi: ProtonAddressesApi,
        logger: Logger
    ) = try {
        addressesApi.getAddresses().addresses
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.warn(t) { "address fetch failed: ${t.javaClass.simpleName}" }
        emptyList()
    }

    /**
     * Returns the unlocked handle, or null if the address key cannot
     * be unlocked. Caller logs + skips. Two paths:
     *
     *   - **Modern**: `Token` is a PGP message encrypted to the user's
     *     primary public; decrypting it yields the address-key
     *     passphrase, accepted only when `Signature` verifies under the
     *     user's keys. [V] WebClients `addressKeys.ts`
     *     (`decryptAddressKeyToken` requires SIGNED_AND_VALID); ADR-0020
     *     amendment. A key whose Token is unsigned or wrongly signed is
     *     skipped: it joins neither the decryption nor the verification
     *     key set.
     *   - **Legacy v1**: `Token == null`; the address key was created
     *     before key-transparency and unlocks with the user
     *     `keyPassword` directly. [V] same WebClients file's
     *     `hasMigratedKeys=false` branch.
     */
    private fun tryUnlockAddressKey(
        ak: AddressKeyDto,
        userKeys: List<UnlockedKey>,
        openPgp: OpenPgpService,
        userKeyPasswordBytes: ByteArray,
        logger: Logger
    ): UnlockedKey? {
        val token = ak.token
        return if (token == null) {
            unlockLegacyV1(ak, userKeyPasswordBytes, logger)
        } else {
            unlockModern(ak, token, userKeys, openPgp, logger)
        }
    }

    private fun unlockLegacyV1(
        ak: AddressKeyDto,
        userKeyPasswordBytes: ByteArray,
        logger: Logger
    ): UnlockedKey? {
        val pp = String(userKeyPasswordBytes, Charsets.UTF_8).toCharArray()
        return try {
            BouncyCastleKeyUnlock.unlock(ak.privateKey, pp)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn(t) { "skipped address key ${ak.id}$SUFFIX_LEGACY: ${t.javaClass.simpleName}" }
            null
        } finally {
            pp.fill(' ')
        }
    }

    // Four returns: no signature, undecryptable token, failed verification, unlock.
    @Suppress("ReturnCount")
    private fun unlockModern(
        ak: AddressKeyDto,
        token: String,
        userKeys: List<UnlockedKey>,
        openPgp: OpenPgpService,
        logger: Logger
    ): UnlockedKey? {
        val signature = ak.signature
        if (signature == null) {
            logger.warn { "skipped address key ${ak.id}: token has no signature" }
            return null
        }
        val tokenPlaintext = try {
            openPgp.decryptAndVerify(
                armoredMessage = token,
                detachedSignature = null,
                decryptionKeys = userKeys.flatMap { it.allPrivateKeys },
                verificationKeys = emptyList()
            ).plaintext
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn(t) { "skipped address key ${ak.id}: token decrypt failed (${t.javaClass.simpleName})" }
            return null
        }
        // [U] Proton signs the Token as a detached signature over the token
        // text; a hex token has no line endings, so text and binary
        // canonicalisation agree. Anything but SIGNED_AND_VALID fails closed.
        val status = openPgp.verifyDetached(
            plaintext = tokenPlaintext,
            armoredSignature = signature,
            verificationKeys = userKeys.map { it.public },
            canonicalText = false,
            stripTrailingSpaces = false
        )
        if (status != VerificationStatus.SIGNED_AND_VALID) {
            tokenPlaintext.fill(0)
            logger.warn { "skipped address key ${ak.id}: token signature $status" }
            return null
        }

        // [U] Token charset assumed US-ASCII (Proton ships hex-encoded
        // random bytes). If a future server ever delivers binary
        // Tokens, this conversion mis-encodes and the unlock fails;
        // the skip-and-continue policy keeps sync alive.
        val tokenChars = String(tokenPlaintext, Charsets.US_ASCII).toCharArray()
        return try {
            BouncyCastleKeyUnlock.unlock(ak.privateKey, tokenChars)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn(t) { "skipped address key ${ak.id}: key unlock failed (${t.javaClass.simpleName})" }
            null
        } finally {
            tokenChars.fill(' ')
            tokenPlaintext.fill(0)
        }
    }
}
