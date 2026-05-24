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
    val public: PgpPublicKeyHandle,
    val allPrivateKeys: List<PgpPrivateKeyHandle> = listOf(private)
)

class KeyUnlockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads an ASCII-armored OpenPGP secret key block and returns handles
 * usable with `OpenPgpService`. Mirrors the role
 * `CryptoProxy.importPrivateKey({ armored, passphrase })` plays in the
 * Proton web client (Plan §2.7 step 13).
 *
 * Extracts and unlocks ALL secret keys in the ring (primary +
 * encryption subkeys). `[V]` Real Proton accounts use split keys:
 * the primary key carries SIGN_DATA | CERTIFY_OTHER, and a subkey
 * carries ENCRYPT_COMMS | ENCRYPT_STORAGE. Contacts are encrypted
 * to the encryption subkey, so the decrypt path needs it.
 *
 * `private` is always the primary (signing) key. `allPrivateKeys`
 * contains all keys in the ring (primary first, then subkeys) so
 * the decrypt path can try each until one matches.
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

        val ring = try {
            val decoded = PGPUtil.getDecoderStream(
                ByteArrayInputStream(armoredPrivateKey.toByteArray(Charsets.US_ASCII))
            )
            val objectFactory = BcPGPObjectFactory(decoded)
            generateSequence { objectFactory.nextObject() }
                .filterIsInstance<PGPSecretKeyRing>()
                .firstOrNull()
                ?: throw KeyUnlockException("no PGPSecretKeyRing found in armored input")
        } catch (kue: KeyUnlockException) {
            throw kue
        } catch (t: Throwable) {
            throw KeyUnlockException("failed to parse armored private key", t)
        }

        val decryptor = try {
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase)
        } catch (pgp: PGPException) {
            throw KeyUnlockException("wrong passphrase or corrupted key material", pgp)
        }

        val allKeys = mutableListOf<PgpPrivateKeyHandle>()
        for (sk in ring.secretKeys) {
            val priv = try {
                sk.extractPrivateKey(decryptor)
            } catch (pgp: PGPException) {
                throw KeyUnlockException("wrong passphrase or corrupted key material", pgp)
            }
            allKeys += PgpPrivateKeyHandle(raw = priv, pubKey = sk.publicKey)
        }
        if (allKeys.isEmpty()) {
            throw KeyUnlockException("key ring contains no secret keys")
        }

        val primaryKey = ring.secretKey
        return UnlockedKey(
            private = allKeys.first { it.raw.keyID == primaryKey.keyID },
            public = PgpPublicKeyHandle(raw = primaryKey.publicKey),
            allPrivateKeys = allKeys
        )
    }
}
