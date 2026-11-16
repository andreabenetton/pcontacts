// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.ByteArrayInputStream
import java.util.Date

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
    val allPrivateKeys: List<PgpPrivateKeyHandle> = listOf(private),
    val encryptionPublicKeys: List<PgpPublicKeyHandle> = emptyList()
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
 * `private` is always the primary (signing) key. `encryptionPublicKeys`
 * holds the one key a message is encrypted to, or none when no key is
 * flagged for encryption. `allPrivateKeys`
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
            throw KeyUnlockException(notOpened(ring.secretKey, pgp), pgp)
        }

        val allKeys = mutableListOf<PgpPrivateKeyHandle>()
        for (sk in ring.secretKeys) {
            val priv = try {
                sk.extractPrivateKey(decryptor)
            } catch (pgp: PGPException) {
                throw KeyUnlockException(notOpened(sk, pgp), pgp)
            }
            allKeys += PgpPrivateKeyHandle(raw = priv, pubKey = sk.publicKey)
        }
        if (allKeys.isEmpty()) {
            throw KeyUnlockException("key ring contains no secret keys")
        }

        val primaryKey = ring.secretKey
        val encryptionKey = encryptionKey(primaryKey.publicKey, ring.publicKeys.asSequence().toList())
        return UnlockedKey(
            private = allKeys.first { it.raw.keyID == primaryKey.keyID },
            public = PgpPublicKeyHandle(raw = primaryKey.publicKey),
            allPrivateKeys = allKeys,
            encryptionPublicKeys = listOfNotNull(encryptionKey?.let { PgpPublicKeyHandle(raw = it) })
        )
    }

    /**
     * The one key a message to this ring is encrypted to, chosen as OpenPGP.js
     * `Key.getEncryptionKey` chooses it `[V]` (openpgpjs `src/key/key.js`, `src/key/helper.js`):
     * the newest usable subkey whose binding signature flags it for encryption, else the
     * primary if its self-signature does. The algorithm alone is not enough: an RSA primary
     * can encrypt but is flagged sign-only on Proton keys, and a card encrypted to it as well
     * was refused with Code 2060, `PgpFormat` (issue #71) `[A]`.
     */
    private fun encryptionKey(primary: PGPPublicKey, keys: List<PGPPublicKey>): PGPPublicKey? {
        val now = Date()
        val subkey = keys
            .filter { !it.isMasterKey && usable(it, now) }
            .filter { allowsEncryption(it, signatures(it, primary.keyID, setOf(PGPSignature.SUBKEY_BINDING))) }
            .maxWithOrNull(compareBy<PGPPublicKey>({ it.creationTime }, { it.algorithm }))
        return subkey ?: primary.takeIf {
            usable(it, now) && allowsEncryption(it, signatures(it, it.keyID, SELF_SIGNATURE_TYPES))
        }
    }

    private fun usable(key: PGPPublicKey, now: Date): Boolean {
        val expiresAt = key.creationTime.time + key.validSeconds * MILLIS_PER_SECOND
        return !key.hasRevocation() && (key.validSeconds == 0L || expiresAt > now.time)
    }

    /** `key`'s signatures of [types] by [issuer] that carry key flags. */
    private fun signatures(key: PGPPublicKey, issuer: Long, types: Set<Int>): List<PGPSignature> =
        key.signatures.asSequence()
            .filterIsInstance<PGPSignature>()
            .filter { it.signatureType in types && it.keyID == issuer && keyFlags(it) != 0 }
            .toList()

    /** The latest flags must allow encryption; a key without any is refused, as OpenPGP.js does by default. */
    private fun allowsEncryption(key: PGPPublicKey, signatures: List<PGPSignature>): Boolean {
        val flags = signatures.maxByOrNull { it.creationTime }?.let(::keyFlags) ?: 0
        return key.isEncryptionKey && flags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0
    }

    private fun keyFlags(signature: PGPSignature): Int = signature.hashedSubPackets?.keyFlags ?: 0

    private const val MILLIS_PER_SECOND = 1000L
    private val SELF_SIGNATURE_TYPES = setOf(
        PGPSignature.DEFAULT_CERTIFICATION,
        PGPSignature.NO_CERTIFICATION,
        PGPSignature.CASUAL_CERTIFICATION,
        PGPSignature.POSITIVE_CERTIFICATION,
        PGPSignature.DIRECT_KEY
    )

    /**
     * Why a key did not open, for a log line (issue #65): the reason BouncyCastle gives plus the
     * key's public metadata — packet version, public-key algorithm, S2K usage and type — which
     * tells a wrong passphrase from a protection this BouncyCastle cannot open. Nothing secret.
     */
    private fun notOpened(key: PGPSecretKey, cause: PGPException): String =
        "wrong passphrase or unsupported key protection (key v${key.publicKey.version}, " +
            "algorithm ${key.publicKey.algorithm}, s2k usage ${key.s2KUsage}, " +
            "s2k type ${key.s2K?.type ?: "none"}): ${cause.message}"
}
