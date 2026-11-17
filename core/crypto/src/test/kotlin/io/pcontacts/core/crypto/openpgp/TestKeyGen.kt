// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.util.Date

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
        val pgpKeyPair: PGPKeyPair = JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PGPPublicKey.RSA_GENERAL, rsaKeyPair, now)

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
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                .build(CharArray(0))
        )

        return TestKey(
            pub = PgpPublicKeyHandle(pgpKeyPair.publicKey),
            priv = PgpPrivateKeyHandle(pgpPrivateKey, pgpKeyPair.publicKey)
        )
    }

    /**
     * Generates an RSA-2048 keypair encrypted under `passphrase` and
     * returns the ASCII-armored secret key block — the same shape
     * `core/v4/users` ships in `User.Keys[i].PrivateKey`.
     */
    fun rsa2048Armored(passphrase: CharArray, identity: String = "pcontacts-test"): String {
        val rsa = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        rsa.initialize(2048)
        val rsaKeyPair = rsa.generateKeyPair()
        val now = Date()
        val pgpKeyPair: PGPKeyPair = JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PGPPublicKey.RSA_GENERAL, rsaKeyPair, now)

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
                .build(passphrase)
        )

        val secretRing: PGPSecretKeyRing = keyRingGen.generateSecretKeyRing()
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored ->
            secretRing.encode(armored)
        }
        return out.toString(Charsets.US_ASCII)
    }

    /**
     * An RSA-2048 ring shaped like an older Proton key: a primary flagged
     * [primaryFlags] (Proton: CERTIFY_OTHER | SIGN_DATA) and one subkey per
     * entry of [subkeys], each created at its date and bound with its flags
     * (Proton: ENCRYPT_COMMS | ENCRYPT_STORAGE). Armored, protected by [passphrase].
     */
    fun rsaRingArmored(passphrase: CharArray, primaryFlags: Int, subkeys: List<Pair<Int, Date>>): String {
        val created = subkeys.minOfOrNull { it.second } ?: Date()
        val primary = JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PGPPublicKey.RSA_GENERAL, rsaKeyPair(), created)
        val checksumCalc = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
        val keyRingGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            primary,
            "pcontacts-test",
            checksumCalc,
            PGPSignatureSubpacketGenerator().apply { setKeyFlags(true, primaryFlags) }.generate(),
            null,
            BcPGPContentSignerBuilder(primary.publicKey.algorithm, HashAlgorithmTags.SHA512),
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, checksumCalc).build(passphrase)
        )
        subkeys.forEach { (flags, date) ->
            keyRingGen.addSubKey(
                JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PGPPublicKey.RSA_GENERAL, rsaKeyPair(), date),
                PGPSignatureSubpacketGenerator().apply { setKeyFlags(true, flags) }.generate(),
                null
            )
        }
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { keyRingGen.generateSecretKeyRing().encode(it) }
        return out.toString(Charsets.US_ASCII)
    }

    fun parseRing(armored: String): PGPSecretKeyRing =
        BcPGPObjectFactory(PGPUtil.getDecoderStream(armored.byteInputStream())).nextObject() as PGPSecretKeyRing

    fun armor(ring: PGPSecretKeyRing): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { ring.encode(it) }
        return out.toString(Charsets.US_ASCII)
    }

    fun primaryPrivate(ring: PGPSecretKeyRing, passphrase: CharArray): PGPPrivateKey {
        val decryptor = BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase)
        return ring.secretKey.extractPrivateKey(decryptor)
    }

    /**
     * A signature of [type] made with [signer]: over [subkey] bound to [primary] for a subkey
     * binding or revocation, else over [userId] of [primary]. With [claimedIssuer] the signature
     * names that key as its issuer although [signer] made it: a forgery.
     */
    @Suppress("LongParameterList") // test fixture: every part of the signature is a parameter
    fun signature(
        type: Int,
        signer: PGPPrivateKey,
        primary: PGPPublicKey,
        subkey: PGPPublicKey? = null,
        userId: String? = null,
        flags: Int = 0,
        keyExpirySeconds: Long = 0L,
        claimedIssuer: Long? = null
    ): PGPSignature {
        val signingKey = claimedIssuer?.let { PGPPrivateKey(it, signer.publicKeyPacket, signer.privateKeyDataPacket) }
            ?: signer
        val generator = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(signer.publicKeyPacket.algorithm, HashAlgorithmTags.SHA512)
        )
        generator.init(type, signingKey)
        generator.setHashedSubpackets(
            PGPSignatureSubpacketGenerator().apply {
                if (flags != 0) setKeyFlags(true, flags)
                if (keyExpirySeconds != 0L) setKeyExpirationTime(true, keyExpirySeconds)
            }.generate()
        )
        return when {
            subkey != null -> generator.generateCertification(primary, subkey)
            userId != null -> generator.generateCertification(userId, primary)
            else -> generator.generateCertification(primary)
        }
    }

    /** [ring] with [secret]'s public key replaced by [public]. */
    fun withPublic(ring: PGPSecretKeyRing, secret: PGPSecretKey, public: PGPPublicKey): PGPSecretKeyRing =
        PGPSecretKeyRing.insertSecretKey(ring, PGPSecretKey.replacePublicKey(secret, public))

    /** [key] without any of its own signatures of [type]. */
    fun withoutSignatures(key: PGPPublicKey, type: Int): PGPPublicKey =
        key.getSignaturesOfType(type).asSequence().toList().fold(key) { stripped, signature ->
            PGPPublicKey.removeCertification(stripped, signature)
        }

    private fun rsaKeyPair() = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        .apply { initialize(2048) }
        .generateKeyPair()
}
