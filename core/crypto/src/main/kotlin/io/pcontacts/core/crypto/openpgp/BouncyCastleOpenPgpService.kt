// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
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
            BcPGPContentSignerBuilder(signingKey.pubKey.algorithm, HashAlgorithmTags.SHA512)
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
        require(decryptionKeys.isNotEmpty()) { "at least one decryption key required" }
        val decoded: InputStream = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredMessage.toByteArray(Charsets.US_ASCII)))
        var objectFactory: PGPObjectFactory = BcPGPObjectFactory(decoded)

        val encList = (objectFactory.nextObject() as? PGPEncryptedDataList)
            ?: error("expected PGPEncryptedDataList at top of message")

        val keyById = decryptionKeys.associateBy { it.raw.keyID }
        val (target, matchedKey) = encList.encryptedDataObjects.asSequence()
            .filterIsInstance<PGPPublicKeyEncryptedData>()
            .mapNotNull { enc -> keyById[enc.keyID]?.let { enc to it } }
            .firstOrNull()
            ?: error("no encrypted data block for any of our ${decryptionKeys.size} key(s)")

        val clearStream = target.getDataStream(BcPublicKeyDataDecryptorFactory(matchedKey.raw))
        objectFactory = BcPGPObjectFactory(clearStream)

        // Strip layers: optional Compressed, then Literal.
        var packet = objectFactory.nextObject()
        if (packet is PGPCompressedData) {
            objectFactory = BcPGPObjectFactory(packet.dataStream)
            packet = objectFactory.nextObject()
        }
        val literal = packet as? PGPLiteralData ?: error("expected PGPLiteralData")
        return literal.inputStream.readBytes()
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
