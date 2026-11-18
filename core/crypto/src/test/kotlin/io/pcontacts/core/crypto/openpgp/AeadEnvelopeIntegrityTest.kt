// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.bouncycastle.bcpg.AEADAlgorithmTags
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * An AEAD message is authenticated chunk by chunk, and its last tag covers the whole of it. The
 * content this app reads sits in the first chunk; the vectors here carry a trailing packet over
 * several later chunks, so a fault in the tail is seen only by reading the envelope to its end
 * (review 2026-11-18, R4). Both AEAD layouts BouncyCastle writes are tried: the RFC 9580 one
 * and the LibrePGP one.
 */
class AeadEnvelopeIntegrityTest {

    private val service = BouncyCastleOpenPgpService()
    private val plaintext = "BEGIN:VCARD\r\nTEL:+15550100\r\nEND:VCARD".toByteArray()

    @Test fun an_intact_aead_message_with_a_tail_decrypts() {
        for (layout in Layout.entries) {
            val message = armor(encrypt(literal(plaintext) + tail(), layout))

            val result = service.decryptAndVerify(message, null, keys, emptyList())

            assertArrayEquals(layout.name, plaintext, result.plaintext)
        }
    }

    @Test fun a_fault_in_the_tail_of_an_aead_message_is_refused() {
        for (layout in Layout.entries) {
            for ((fault, bytes) in faulty(encrypt(literal(plaintext) + tail(), layout))) {
                assertThrows("${layout.name} $fault", Exception::class.java) {
                    service.decryptAndVerify(armor(bytes), null, keys, emptyList())
                }
            }
        }
    }

    @Test fun a_fault_in_the_tail_of_a_compressed_aead_message_is_refused() {
        for (layout in Layout.entries) {
            for ((fault, bytes) in faulty(encrypt(compressed(literal(plaintext)) + tail(), layout))) {
                assertThrows("${layout.name} $fault", Exception::class.java) {
                    service.decryptAndVerify(armor(bytes), null, keys, emptyList())
                }
            }
        }
    }

    @Test fun an_intact_inline_signed_aead_message_with_a_tail_verifies() {
        for (layout in Layout.entries) {
            val message = armor(encrypt(inlineSigned(plaintext) + tail(), layout))

            val result = service.decryptAndVerifyInline(message, keys, listOf(key.pub))

            assertArrayEquals(layout.name, plaintext, result.plaintext)
            assertEquals(layout.name, VerificationStatus.SIGNED_AND_VALID, result.verificationStatus)
        }
    }

    @Test fun a_fault_in_the_tail_of_an_inline_signed_aead_message_is_refused() {
        for (layout in Layout.entries) {
            for ((fault, bytes) in faulty(encrypt(inlineSigned(plaintext) + tail(), layout))) {
                assertThrows("${layout.name} $fault", Exception::class.java) {
                    service.decryptAndVerifyInline(armor(bytes), keys, listOf(key.pub))
                }
            }
        }
    }

    private enum class Layout { RFC9580, LIBREPGP }

    /** The message with its last tag altered, with a late chunk altered, and cut short of its end. */
    private fun faulty(message: ByteArray): Map<String, ByteArray> = mapOf(
        "last tag altered" to flipped(message, message.size - 1),
        "late chunk altered" to flipped(message, message.size - LATE_CHUNK_OFFSET),
        "cut short" to message.copyOf(message.size - CUT_BYTES)
    )

    private fun encrypt(inner: ByteArray, layout: Layout): ByteArray {
        val builder = BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setSecureRandom(SecureRandom())
        when (layout) {
            Layout.RFC9580 -> builder.setUseV6AEAD()
            Layout.LIBREPGP -> builder.setUseV5AEAD()
        }
        builder.setWithAEAD(AEADAlgorithmTags.OCB, CHUNK_SIZE_EXPONENT)
        val generator = PGPEncryptedDataGenerator(builder)
        generator.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(key.pub.raw))
        val out = ByteArrayOutputStream()
        generator.open(out, ByteArray(STREAM_BUFFER)).use { it.write(inner) }
        return out.toByteArray()
    }

    private fun literal(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        PGPLiteralDataGenerator().open(out, PGPLiteralData.BINARY, "_", data.size.toLong(), Date())
            .use { it.write(data) }
        return out.toByteArray()
    }

    /** A second packet nothing reads, long enough to fill several chunks after the first. */
    private fun tail(): ByteArray = literal(ByteArray(TAIL_BYTES) { it.toByte() })

    private fun compressed(packets: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        PGPCompressedDataGenerator(PGPCompressedData.ZIP).open(out).use { it.write(packets) }
        return out.toByteArray()
    }

    private fun inlineSigned(data: ByteArray): ByteArray {
        val generator = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(key.pub.raw.algorithm, HashAlgorithmTags.SHA512),
            key.pub.raw
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, key.priv.raw)
        generator.update(data)
        val out = ByteArrayOutputStream()
        BCPGOutputStream(out).use { packets ->
            generator.generateOnePassVersion(false).encode(packets)
            packets.write(literal(data))
            generator.generate().encode(packets)
        }
        return out.toByteArray()
    }

    private fun flipped(bytes: ByteArray, index: Int): ByteArray =
        bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

    private fun armor(bytes: ByteArray): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { it.write(bytes) }
        return out.toString(Charsets.US_ASCII)
    }

    companion object {
        private lateinit var key: TestKeyGen.TestKey
        private val keys get() = listOf(key.priv)

        /** Chunks of 2^6 = 64 bytes, the smallest BouncyCastle writes. */
        private const val CHUNK_SIZE_EXPONENT = 6
        private const val TAIL_BYTES = 2000
        private const val LATE_CHUNK_OFFSET = 300
        private const val CUT_BYTES = 10
        private const val STREAM_BUFFER = 256

        @BeforeClass
        @JvmStatic
        fun makeKey() {
            key = TestKeyGen.rsa2048()
        }
    }
}
