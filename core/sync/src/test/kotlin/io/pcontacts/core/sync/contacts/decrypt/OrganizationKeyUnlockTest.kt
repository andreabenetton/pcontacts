// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.proton.api.addresses.GetAddressesResponse
import io.pcontacts.core.proton.api.addresses.ProtonAddressesApi
import io.pcontacts.core.proton.api.users.GetKeySaltsResponse
import io.pcontacts.core.proton.api.users.GetUserResponse
import io.pcontacts.core.proton.api.users.ProtonUsersApi
import io.pcontacts.core.proton.api.users.UserDto
import io.pcontacts.core.proton.api.users.UserKeyDto
import io.pcontacts.core.storage.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * User keys of an organization member open with their member token, as WebClients
 * `getDecryptedUserKeysHelper` opens them (review 2026-11-17, F8). The keys and tokens here are
 * generated; no live organization account has been tried.
 */
class OrganizationKeyUnlockTest {

    private val openPgp = BouncyCastleOpenPgpService()

    @Test fun a_member_user_key_opens_with_its_token_from_the_organization_key() = runTest {
        val organization = TestKeys.armoredKey(KEY_PASSWORD.toCharArray())
        val userKey = TestKeys.armoredKey(TOKEN.toCharArray())
        val token = memberToken(
            TOKEN,
            recipient = organization,
            signer = organization,
            signerPassphrase = KEY_PASSWORD
        )

        val keys = unlock(UserDto(id = "u1", keys = listOf(key(userKey, token)), organizationPrivateKey = organization))

        assertEquals(1, keys.verificationKeys.size)
    }

    @Test fun a_token_not_signed_by_the_organization_key_does_not_open_the_key() = runTest {
        val organization = TestKeys.armoredKey(KEY_PASSWORD.toCharArray())
        val someoneElse = TestKeys.armoredKey(OTHER.toCharArray())
        val userKey = TestKeys.armoredKey(TOKEN.toCharArray())
        val token = memberToken(TOKEN, recipient = organization, signer = someoneElse, signerPassphrase = OTHER)
        val user = UserDto(id = "u1", keys = listOf(key(userKey, token)), organizationPrivateKey = organization)

        val failure = assertThrows(DecryptUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { unlock(user) }
        }

        assertEquals("KEY_UNLOCK_FAILED", failure.message)
    }

    @Test fun an_organization_key_that_does_not_open_stops_the_run() = runTest {
        val organization = TestKeys.armoredKey(OTHER.toCharArray())
        val userKey = TestKeys.armoredKey(KEY_PASSWORD.toCharArray())
        val user = UserDto(id = "u1", keys = listOf(key(userKey, token = null)), organizationPrivateKey = organization)

        assertThrows(DecryptUnavailableException::class.java) { kotlinx.coroutines.runBlocking { unlock(user) } }
    }

    @Test fun an_ordinary_account_opens_its_key_with_the_key_password_as_before() = runTest {
        val userKey = TestKeys.armoredKey(KEY_PASSWORD.toCharArray())

        val keys = unlock(UserDto(id = "u1", keys = listOf(key(userKey, token = null))))

        assertEquals(1, keys.verificationKeys.size)
    }

    private suspend fun unlock(user: UserDto) = ContactDecryptBootstrap.unlockAllKeys(
        InMemorySecretStore().apply { setKeyPassword(KEY_PASSWORD.toByteArray()) },
        object : ProtonUsersApi {
            override suspend fun getUser() = GetUserResponse(code = 1000, user = user)
            override suspend fun getKeySalts(): GetKeySaltsResponse = error("not used")
        },
        object : ProtonAddressesApi {
            override suspend fun getAddresses() = GetAddressesResponse(code = 1000)
        },
        openPgp
    )

    private fun key(armored: String, token: String?) =
        UserKeyDto(id = "k1", primary = 1, active = 1, privateKey = armored, token = token)

    /** A member token as `encryptMemberToken` lays it out: encrypted to [recipient], signed inline by [signer]. */
    private fun memberToken(token: String, recipient: String, signer: String, signerPassphrase: String): String {
        val signerRing = ring(signer)
        val signingKey = signerRing.secretKey.extractPrivateKey(
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(signerPassphrase.toCharArray())
        )
        val generator = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(signerRing.publicKey.algorithm, HashAlgorithmTags.SHA512),
            signerRing.publicKey
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, signingKey)
        val bytes = token.toByteArray()
        generator.update(bytes)
        val inner = ByteArrayOutputStream()
        BCPGOutputStream(inner).use { out ->
            generator.generateOnePassVersion(false).encode(out)
            PGPLiteralDataGenerator().open(out, PGPLiteralData.BINARY, "", bytes.size.toLong(), Date())
                .use { it.write(bytes) }
            generator.generate().encode(out)
        }
        val encryptor = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
        )
        encryptor.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(ring(recipient).publicKey))
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored ->
            encryptor.open(armored, inner.size().toLong()).use { it.write(inner.toByteArray()) }
        }
        return out.toString(Charsets.US_ASCII)
    }

    private fun ring(armored: String) =
        BcPGPObjectFactory(PGPUtil.getDecoderStream(armored.byteInputStream())).nextObject() as PGPSecretKeyRing

    private companion object {
        const val KEY_PASSWORD = "bcrypt-derived-key-password"
        const val TOKEN = "bWVtYmVyLXRva2VuLWJhc2U2NC1vZi0xMjgtcmFuZG9tLWJ5dGVz"
        const val OTHER = "another-passphrase"
    }
}
