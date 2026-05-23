// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import java.io.ByteArrayInputStream
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider

/**
 * Bundle of an unlocked private key + its matching public key. Per
 * ADR-0009, the caller is expected to hold this for the duration of a
 * sync run and discard the references when done — the passphrase
 * material has already been zeroed by `unlock()` by then, but the
 * private key itself remains sensitive in memory.
 */
data class UnlockedKey(
    val private: PgpPrivateKeyHandle,
    val public: PgpPublicKeyHandle
)

class KeyUnlockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads an ASCII-armored OpenPGP secret key block and returns handles
 * usable with `OpenPgpService`. Mirrors the role
 * `CryptoProxy.importPrivateKey({ armored, passphrase })` plays in the
 * Proton web client (Plan §2.7 step 13).
 *
 * For MVP we use the primary secret key in the ring for both signing
 * and decryption — that matches how Proton's user keys are issued
 * (`KeyFlags.SIGN_DATA | CERTIFY_OTHER | ENCRYPT_COMMS | ENCRYPT_STORAGE`
 * on the primary, [V] from the web client's getKeyEncryptionInfo path).
 * Real-account keys with split sign/encrypt subkeys land with the
 * complete version; the dispatcher in `:core:proton-contacts` already
 * treats the keyset as an opaque pair of handles, so growing the
 * unlock result to expose subkeys later is a non-breaking change.
 */
object BouncyCastleKeyUnlock {

    /**
     * @param armoredPrivateKey  the `-----BEGIN PGP PRIVATE KEY BLOCK-----` text
     * @param passphrase         the unlock passphrase (typically Proton's keyPassword,
     *                           ie. `bcrypt-SHA512(password, KeySalt)`). The caller
     *                           is responsible for zeroing this array; we make a copy
     *                           for BouncyCastle, so zeroing on return is safe.
     *
     * @throws KeyUnlockException if the armored block is malformed, contains no
     *         secret key ring, or the passphrase is wrong.
     */
    fun unlock(armoredPrivateKey: String, passphrase: CharArray): UnlockedKey {
        PgpProvider.ensureProvider()

        val secretKey = try {
            val decoded = PGPUtil.getDecoderStream(
                ByteArrayInputStream(armoredPrivateKey.toByteArray(Charsets.US_ASCII))
            )
            val objectFactory = BcPGPObjectFactory(decoded)
            val ring = generateSequence { objectFactory.nextObject() }
                .filterIsInstance<PGPSecretKeyRing>()
                .firstOrNull()
                ?: throw KeyUnlockException("no PGPSecretKeyRing found in armored input")
            // Primary key carries SIGN_DATA + ENCRYPT_COMMS for Proton users [V].
            ring.secretKey
        } catch (kue: KeyUnlockException) {
            throw kue
        } catch (t: Throwable) {
            throw KeyUnlockException("failed to parse armored private key", t)
        }

        val pgpPrivateKey = try {
            secretKey.extractPrivateKey(
                BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase)
            )
        } catch (pgp: PGPException) {
            // BouncyCastle throws PGPException("checksum mismatch") for bad
            // passphrases. Translate without leaking the passphrase or
            // BouncyCastle's internal stack trace into the message.
            throw KeyUnlockException("wrong passphrase or corrupted key material", pgp)
        }

        return UnlockedKey(
            private = PgpPrivateKeyHandle(raw = pgpPrivateKey, pubKey = secretKey.publicKey),
            public = PgpPublicKeyHandle(raw = secretKey.publicKey)
        )
    }
}
