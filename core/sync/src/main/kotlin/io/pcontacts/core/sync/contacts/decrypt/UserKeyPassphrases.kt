// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.BouncyCastleKeyUnlock
import io.pcontacts.core.crypto.openpgp.KeyUnlockException
import io.pcontacts.core.crypto.openpgp.OpenPgpService
import io.pcontacts.core.crypto.openpgp.UnlockedKey
import io.pcontacts.core.crypto.openpgp.VerificationStatus
import io.pcontacts.core.proton.api.users.UserDto
import io.pcontacts.core.proton.api.users.UserKeyDto

/**
 * The passphrase that opens each user key, found as WebClients `getDecryptedUserKeysHelper`
 * finds it `[V]` (`packages/shared/lib/keys/getDecryptedUserKeys.ts`, `memberToken.ts`,
 * `getDecryptedOrganizationKey.ts` @ 444df12):
 *
 * - on an ordinary account, the key password;
 * - on an account with an organization key, which opens with the key password, a user key that
 *   carries a `Token` opens with that member token: the token is decrypted with the
 *   organization key and must be signed by it (`decryptMemberToken`); a user key without one
 *   opens with the key password.
 *
 * `[A]` Not validated against a live organization account (review 2026-11-17, F8). Anything
 * that does not hold fails closed, as in WebClients: the key does not open.
 */
internal class UserKeyPassphrases private constructor(
    private val keyPassword: CharArray,
    private val organizationKey: UnlockedKey?,
    private val openPgp: OpenPgpService
) {

    /**
     * The passphrase for [key], which the caller wipes. A member token that does not hold throws
     * [KeyUnlockException]; any failure to decrypt it (malformed, not for this key, integrity) is
     * the same refusal.
     */
    @Suppress("TooGenericExceptionCaught")
    fun forKey(key: UserKeyDto): CharArray {
        val token = key.token
        val organization = organizationKey
        if (token == null || organization == null) return keyPassword.copyOf()
        val result = try {
            openPgp.decryptAndVerifyInline(token, organization.allPrivateKeys, listOf(organization.public))
        } catch (e: Exception) {
            throw KeyUnlockException("member token of user key ${key.id} did not decrypt", e)
        }
        try {
            if (result.verificationStatus != VerificationStatus.SIGNED_AND_VALID) {
                throw KeyUnlockException(
                    "member token of user key ${key.id} is not signed by the organization key " +
                        "(${result.verificationStatus})"
                )
            }
            return String(result.plaintext, Charsets.UTF_8).toCharArray()
        } finally {
            result.plaintext.fill(0)
        }
    }

    /** Wipes the key password copy this holds; [forKey] must not be called after. */
    fun wipe() {
        keyPassword.fill(' ')
    }

    companion object {
        /**
         * Throws [KeyUnlockException] when the user has an organization key that does not open with
         * [keyPassword]: as in WebClients, no user key opens then. [keyPassword] is kept until [wipe].
         */
        fun of(user: UserDto, keyPassword: CharArray, openPgp: OpenPgpService): UserKeyPassphrases {
            val organization = user.organizationPrivateKey?.let { BouncyCastleKeyUnlock.unlock(it, keyPassword) }
            return UserKeyPassphrases(keyPassword, organization, openPgp)
        }
    }
}
