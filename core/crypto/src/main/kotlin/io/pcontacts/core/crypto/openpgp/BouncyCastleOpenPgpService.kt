// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom

class BouncyCastleOpenPgpService : OpenPgpService {

    init {
        PgpProvider.ensureProvider()
    }

    override fun encryptAndSignDetached(
        plaintext: ByteArray,
        encryptionKeys: List<PgpPublicKeyHandle>,
        signingKey: PgpPrivateKeyHandle
    ): EncryptedSignedResult {
        require(encryptionKeys.isNotEmpty()) { "at least one encryption key required" }

        val literalOut = ByteArrayOutputStream()
        val literalGen = PGPLiteralDataGenerator()
        literalGen.open(literalOut, PGPLiteralData.BINARY, "_", plaintext.size.toLong(), java.util.Date())
            .use { it.write(plaintext) }

        val compressedOut = ByteArrayOutputStream()
        val compressedGen = PGPCompressedDataGenerator(PGPCompressedData.ZIP)
        compressedGen.open(compressedOut).use { it.write(literalOut.toByteArray()) }

        val encryptedOut = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(encryptedOut)
        val encGen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
        )
        encryptionKeys.forEach { encGen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(it.raw)) }
        encGen.open(armored, compressedOut.size().toLong()).use { it.write(compressedOut.toByteArray()) }
        armored.close()

        val detachedSig = signDetached(plaintext, signingKey, canonicalText = false, stripTrailingSpaces = false)

        return EncryptedSignedResult(
            armoredMessage = encryptedOut.toByteArray().toString(Charsets.US_ASCII),
            armoredDetachedSignature = detachedSig
        )
    }

    override fun signDetached(
        plaintext: ByteArray,
        signingKey: PgpPrivateKeyHandle,
        canonicalText: Boolean,
        stripTrailingSpaces: Boolean
    ): String {
        val signatureType = if (canonicalText) PGPSignature.CANONICAL_TEXT_DOCUMENT else PGPSignature.BINARY_DOCUMENT
        val data = if (canonicalText) TextCanonicalization.canonicalize(plaintext, stripTrailingSpaces) else plaintext

        val sigGen = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(signingKey.pubKey.algorithm, HashAlgorithmTags.SHA512),
            signingKey.pubKey
        )
        sigGen.init(signatureType, signingKey.raw)

        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out)
        val bcpgOut = BCPGOutputStream(armored)

        sigGen.update(data)
        sigGen.generate().encode(bcpgOut)

        bcpgOut.close()
        armored.close()
        return out.toByteArray().toString(Charsets.US_ASCII)
    }

    override fun decryptAndVerify(
        armoredMessage: String,
        detachedSignature: String?,
        decryptionKeys: List<PgpPrivateKeyHandle>,
        verificationKeys: List<PgpPublicKeyHandle>
    ): VerifiedDecryptResult {
        val plaintext = decryptToBytes(armoredMessage, decryptionKeys)
        val status = when {
            detachedSignature == null -> VerificationStatus.NOT_SIGNED
            verificationKeys.isEmpty() -> VerificationStatus.SIGNED_NO_VERIFIER
            else -> verifyDetached(plaintext, detachedSignature, verificationKeys,
                                   canonicalText = false, stripTrailingSpaces = false)
        }
        return VerifiedDecryptResult(plaintext, status)
    }

    override fun verifyDetached(
        plaintext: ByteArray,
        armoredSignature: String,
        verificationKeys: List<PgpPublicKeyHandle>,
        canonicalText: Boolean,
        stripTrailingSpaces: Boolean
    ): VerificationStatus {
        if (verificationKeys.isEmpty()) return VerificationStatus.SIGNED_NO_VERIFIER

        val sig = parseDetachedSignature(armoredSignature) ?: return VerificationStatus.NOT_SIGNED

        val verifier = verificationKeys.firstOrNull { it.raw.keyID == sig.keyID }
            ?: return VerificationStatus.SIGNED_NO_VERIFIER

        val data = if (canonicalText) TextCanonicalization.canonicalize(plaintext, stripTrailingSpaces) else plaintext

        sig.init(BcPGPContentVerifierBuilderProvider(), verifier.raw)
        sig.update(data)
        return if (sig.verify()) VerificationStatus.SIGNED_AND_VALID else VerificationStatus.SIGNED_INVALID
    }

    // --- internals ---

    private fun decryptToBytes(armoredMessage: String, decryptionKeys: List<PgpPrivateKeyHandle>): ByteArray {
        val (target, clearStream) = open(armoredMessage, decryptionKeys)
        val literal = innerPackets(clearStream).second as? PGPLiteralData ?: error("expected PGPLiteralData")
        val plaintext = literal.inputStream.readBytes()
        checkIntegrity(target, plaintext)
        return plaintext
    }

    override fun decryptAndVerifyInline(
        armoredMessage: String,
        decryptionKeys: List<PgpPrivateKeyHandle>,
        verificationKeys: List<PgpPublicKeyHandle>
    ): VerifiedDecryptResult {
        val (target, clearStream) = open(armoredMessage, decryptionKeys)
        val (factory, first) = innerPackets(clearStream)
        val onePass = (first as? PGPOnePassSignatureList)?.takeIf { !it.isEmpty }?.get(0)
        val literal = (if (onePass != null) factory.nextObject() else first) as? PGPLiteralData
            ?: error("expected PGPLiteralData")
        val verifier = onePass?.let { ops -> verificationKeys.firstOrNull { it.raw.keyID == ops.keyID } }
        verifier?.let { onePass.init(BcPGPContentVerifierBuilderProvider(), it.raw) }
        val plaintext = literal.inputStream.readBytes()
        verifier?.let { onePass.update(plaintext) }
        val signature = onePass?.let { (factory.nextObject() as? PGPSignatureList)?.takeIf { !it.isEmpty }?.get(0) }
        checkIntegrity(target, plaintext)
        val status = when {
            onePass == null -> VerificationStatus.NOT_SIGNED
            verifier == null -> VerificationStatus.SIGNED_NO_VERIFIER
            signature != null && onePass.verify(signature) -> VerificationStatus.SIGNED_AND_VALID
            else -> VerificationStatus.SIGNED_INVALID
        }
        return VerifiedDecryptResult(plaintext, status)
    }

    /**
     * The encrypted packet meant for one of [decryptionKeys], and its decrypted stream. `[V]`
     * OpenPGP.js refuses an encrypted packet without integrity protection by default
     * (`config.allowUnauthenticatedMessages = false`); so does this (review 2026-11-17, F2).
     */
    private fun open(
        armoredMessage: String,
        decryptionKeys: List<PgpPrivateKeyHandle>
    ): Pair<PGPPublicKeyEncryptedData, InputStream> {
        require(decryptionKeys.isNotEmpty()) { "at least one decryption key required" }
        val decoded: InputStream = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredMessage.toByteArray(Charsets.US_ASCII)))
        val objectFactory: PGPObjectFactory = BcPGPObjectFactory(decoded)

        val encList = (objectFactory.nextObject() as? PGPEncryptedDataList)
            ?: error("expected PGPEncryptedDataList at top of message")

        val keyById = decryptionKeys.associateBy { it.raw.keyID }
        val (target, matchedKey) = encList.encryptedDataObjects.asSequence()
            .filterIsInstance<PGPPublicKeyEncryptedData>()
            .mapNotNull { enc -> keyById[enc.keyIdentifier.keyId]?.let { enc to it } }
            .firstOrNull()
            ?: error("no encrypted data block for any of our ${decryptionKeys.size} key(s)")

        check(target.isIntegrityProtected || target.isAEAD) { "encrypted data has no integrity protection" }
        val keyPair = PGPKeyPair(matchedKey.pubKey, matchedKey.raw)
        return target to target.getDataStream(BcPublicKeyDataDecryptorFactory(keyPair))
    }

    /** The packets inside the encryption, past an optional compression layer, and the first of them. */
    private fun innerPackets(clearStream: InputStream): Pair<PGPObjectFactory, Any?> {
        val outer = BcPGPObjectFactory(clearStream)
        val first = outer.nextObject()
        if (first !is PGPCompressedData) return outer to first
        val inner = BcPGPObjectFactory(first.dataStream)
        return inner to inner.nextObject()
    }

    /**
     * Reading the literal data does not check the MDC; verify() drains the rest and does. An AEAD
     * packet checks its tags while it is read, and a bad one has already thrown. On failure the
     * plaintext is wiped.
     */
    private fun checkIntegrity(target: PGPPublicKeyEncryptedData, plaintext: ByteArray) {
        if (!target.isAEAD && !target.verify()) {
            plaintext.fill(0)
            error("encrypted data failed its integrity check")
        }
    }

    private fun parseDetachedSignature(armoredSignature: String): PGPSignature? {
        val decoded: InputStream = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredSignature.toByteArray(Charsets.US_ASCII)))
        val factory = BcPGPObjectFactory(decoded)
        val obj = factory.nextObject() ?: return null
        return when (obj) {
            is PGPSignatureList -> if (obj.isEmpty) null else obj.get(0)
            is PGPSignature -> obj
            else -> null
        }
    }

    @Suppress("unused")
    private val fingerprintCalculator = BcKeyFingerprintCalculator()
}
