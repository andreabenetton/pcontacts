// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import java.security.KeyPairGenerator
import java.util.Date
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
 * Test-only key generator. Produces an RSA-2048 OpenPGP keypair (chosen
 * for portability — the production code accepts whatever a real Proton
 * account hands us, including ECC). Returns ready-to-use handles for
 * the four OpenPGP operations.
 */
internal object TestKeyGen {

    init {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(BouncyCastleProvider())
        }
    }

    data class TestKey(val pub: PgpPublicKeyHandle, val priv: PgpPrivateKeyHandle)

    fun rsa2048(identity: String = "pcontacts-test"): TestKey {
        val rsa = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        rsa.initialize(2048)
        val rsaKeyPair = rsa.generateKeyPair()
        val now = Date()
        val pgpKeyPair: PGPKeyPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, rsaKeyPair, now)

        // PGPSecretKey checksum calculation requires SHA-1; signing itself
        // uses SHA-512 via BcPGPContentSignerBuilder below.
        val checksumCalc = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
        val signSubpacket = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.SIGN_DATA or KeyFlags.CERTIFY_OTHER or KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
            setPreferredSymmetricAlgorithms(false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256))
            setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA512))
        }

        val keyRingGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKeyPair,
            identity,
            checksumCalc,
            signSubpacket.generate(),
            null,
            BcPGPContentSignerBuilder(pgpKeyPair.publicKey.algorithm, HashAlgorithmTags.SHA512),
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, checksumCalc)
                .build(CharArray(0))   // no passphrase — test fixture only
        )

        val secretRing = keyRingGen.generateSecretKeyRing()
        val secretKey = secretRing.secretKey

        val pgpPrivateKey = secretKey.extractPrivateKey(
            org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                .build(CharArray(0))
        )

        return TestKey(
            pub = PgpPublicKeyHandle(pgpKeyPair.publicKey),
            priv = PgpPrivateKeyHandle(pgpPrivateKey, pgpKeyPair.publicKey)
        )
    }
}
