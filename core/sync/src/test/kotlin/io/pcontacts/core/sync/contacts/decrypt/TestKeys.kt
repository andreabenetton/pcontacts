// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.BouncyCastleKeyUnlock
import io.pcontacts.core.crypto.openpgp.UnlockedKey
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.util.Date
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair

/**
 * Shared test fixture for the decrypt-path tests. Generates real RSA
 * keypairs encrypted under a passphrase — the exact shape Proton ships
 * via `User.Keys[i].PrivateKey` — and exposes the public unlock surface
 * (BouncyCastleKeyUnlock) so the handle constructors can stay
 * `internal` to :core:crypto.
 *
 * Lives in :core:sync test sources rather than :core:crypto test
 * fixtures because that would require adding the `java-test-fixtures`
 * Gradle plugin; this single shared file is cheaper for now.
 */
internal object TestKeys {

    init {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(BouncyCastleProvider())
        }
    }

    /** Generates and returns an ASCII-armored secret-key block encrypted under `passphrase`. */
    fun armoredKey(passphrase: CharArray): String {
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(2048)
        val rsa = kpg.generateKeyPair()
        val pgpKp: PGPKeyPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, rsa, Date())
        val sha1 = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
        val subpacket = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(
                false,
                KeyFlags.SIGN_DATA or KeyFlags.ENCRYPT_COMMS or
                    KeyFlags.CERTIFY_OTHER or KeyFlags.ENCRYPT_STORAGE
            )
        }
        val ring = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKp,
            "pcontacts-test",
            sha1,
            subpacket.generate(),
            null,
            BcPGPContentSignerBuilder(pgpKp.publicKey.algorithm, HashAlgorithmTags.SHA512),
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1).build(passphrase)
        ).generateSecretKeyRing()

        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { ring.encode(it) }
        return out.toString(Charsets.US_ASCII)
    }

    /** Generates an armored key + immediately unlocks it. Caller gets matching armored / handles pair. */
    fun armoredAndUnlocked(passphrase: CharArray): Pair<String, UnlockedKey> {
        val armored = armoredKey(passphrase.copyOf())
        val unlocked = BouncyCastleKeyUnlock.unlock(armored, passphrase.copyOf())
        return armored to unlocked
    }
}
