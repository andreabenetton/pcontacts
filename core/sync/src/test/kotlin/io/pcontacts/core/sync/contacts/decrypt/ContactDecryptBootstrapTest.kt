// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.users.GetKeySaltsResponse
import io.pcontacts.core.proton.api.users.GetUserResponse
import io.pcontacts.core.proton.api.users.ProtonUsersApi
import io.pcontacts.core.proton.api.users.UserDto
import io.pcontacts.core.proton.api.users.UserKeyDto
import io.pcontacts.core.storage.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end proof that the wiring works with real BouncyCastle crypto:
 * a real PGP keypair, a real armored secret-key block fed via the
 * fake ProtonUsersApi, real encrypted-and-signed + signed Cards built
 * via BouncyCastleOpenPgpService, and the full ContactDecryptBootstrap
 * → ContactProcessor pipeline producing a DecryptedContact whose
 * fullName and email match the plaintext we encrypted.
 */
class ContactDecryptBootstrapTest {

    private val openPgp = BouncyCastleOpenPgpService()

    @Test fun decrypts_real_cards_end_to_end_with_keys_unlocked_from_SecretStore() = runTest {
        val passphrase = "P4ss-XYZ-correct-horse".toCharArray()
        val (armored, unlocked) = TestKeys.armoredAndUnlocked(passphrase)

        val secretStore = InMemorySecretStore().apply {
            setKeyPassword(String(passphrase).toByteArray(Charsets.UTF_8))
        }
        val usersApi = FakeUsersApi(armoredPrivateKey = armored)

        // SIGNED card carries the vCard UID + FN + structured N pieces.
        val signedPlaintext = """
            BEGIN:VCARD
            VERSION:4.0
            UID:urn:uuid:trusted-alice
            FN:Alice Doe
            N:Doe;Alice;Marie;Dr;PhD
            END:VCARD
        """.trimIndent()
        val signature = openPgp.signDetached(
            plaintext = signedPlaintext.toByteArray(Charsets.UTF_8),
            signingKey = unlocked.private
        )
        val signedCard = ContactCardDto(type = 2, data = signedPlaintext, signature = signature)

        // ENCRYPTED_AND_SIGNED card carries the email + a couple of phones.
        val encryptedPlaintext = """
            BEGIN:VCARD
            VERSION:4.0
            EMAIL;TYPE=work:alice.work@proton.me
            TEL;TYPE=home:+1 555 0100
            TEL;TYPE=cell;PREF=1:+1 555 0101
            END:VCARD
        """.trimIndent()
        val encrypted = openPgp.encryptAndSignDetached(
            plaintext = encryptedPlaintext.toByteArray(Charsets.UTF_8),
            encryptionKeys = listOf(unlocked.public),
            signingKey = unlocked.private
        )
        val encryptedSignedCard = ContactCardDto(
            type = 3,
            data = encrypted.armoredMessage,
            signature = encrypted.armoredDetachedSignature
        )

        val contact = ContactDto(id = "c-1", cards = listOf(signedCard, encryptedSignedCard))

        // Act under test — bootstrap unlocks + builds the processor.
        val processor = ContactDecryptBootstrap.createProcessor(secretStore, usersApi, openPgp)
        val out = processor.process(contact)

        assertEquals("c-1", out.protonContactId)
        assertEquals("urn:uuid:trusted-alice", out.protonUid)
        assertEquals("Alice Doe", out.fullName)
        assertEquals(setOf("alice.work@proton.me"), out.emails.map { it.address }.toSet())

        // Structured-name pieces from the SIGNED card survive the
        // real decrypt → merge → project chain.
        val sn = out.structuredName
        assertNotNull(sn)
        assertEquals("Alice", sn!!.given)
        assertEquals("Doe", sn.family)

        // Phones from the ENCRYPTED_AND_SIGNED card decrypted successfully
        // and surfaced with primary-flag intact.
        assertEquals(2, out.phones.size)
        val byNumber = out.phones.associateBy { it.number }
        assertTrue("cell + PREF=1 is primary", byNumber["+1 555 0101"]!!.isPrimary)

        assertTrue("both cards must verify under real keys", out.verified)
        assertEquals(2, out.cardCount)
        assertEquals(0, out.unverifiedCardCount)
    }

    @Test fun createProcessor_without_persisted_keyPassword_throws_KEY_PASSWORD_MISSING() = runTest {
        val secretStore = InMemorySecretStore()    // empty
        val usersApi = FakeUsersApi(armoredPrivateKey = "irrelevant")
        try {
            ContactDecryptBootstrap.createProcessor(secretStore, usersApi, openPgp)
            fail("expected DecryptUnavailableException(KEY_PASSWORD_MISSING)")
        } catch (ex: DecryptUnavailableException) {
            assertEquals("KEY_PASSWORD_MISSING", ex.message)
        }
    }

    @Test fun createProcessor_with_no_primary_active_key_throws_NO_PRIMARY_KEY() = runTest {
        val secretStore = InMemorySecretStore().apply {
            setKeyPassword("anything".toByteArray())
        }
        val usersApi = FakeUsersApi(armoredPrivateKey = "irrelevant", primaryFlag = 0)
        try {
            ContactDecryptBootstrap.createProcessor(secretStore, usersApi, openPgp)
            fail("expected DecryptUnavailableException(NO_PRIMARY_KEY)")
        } catch (ex: DecryptUnavailableException) {
            assertEquals("NO_PRIMARY_KEY", ex.message)
        }
    }

    @Test fun createProcessor_with_wrong_passphrase_throws_KEY_UNLOCK_FAILED() = runTest {
        val rightPassphrase = "P4ss-Z73-correct".toCharArray()
        val wrongPassphrase = "P4ss-Z73-stale".toCharArray()
        val armored = TestKeys.armoredKey(rightPassphrase)

        val secretStore = InMemorySecretStore().apply {
            setKeyPassword(String(wrongPassphrase).toByteArray())
        }
        val usersApi = FakeUsersApi(armoredPrivateKey = armored)
        try {
            ContactDecryptBootstrap.createProcessor(secretStore, usersApi, openPgp)
            fail("expected DecryptUnavailableException(KEY_UNLOCK_FAILED)")
        } catch (ex: DecryptUnavailableException) {
            assertEquals("KEY_UNLOCK_FAILED", ex.message)
            assertNotNull(ex.cause)
        }
    }

    private class FakeUsersApi(
        private val armoredPrivateKey: String,
        private val primaryFlag: Int = 1
    ) : ProtonUsersApi {
        override suspend fun getUser(): GetUserResponse = GetUserResponse(
            code = 1000,
            user = UserDto(
                id = "user-1",
                keys = listOf(
                    UserKeyDto(
                        id = "key-1",
                        primary = primaryFlag,
                        active = 1,
                        privateKey = armoredPrivateKey
                    )
                )
            )
        )
        override suspend fun getKeySalts(): GetKeySaltsResponse =
            error("ContactDecryptBootstrap does not need salts — keyPassword is already persisted")
    }
}
