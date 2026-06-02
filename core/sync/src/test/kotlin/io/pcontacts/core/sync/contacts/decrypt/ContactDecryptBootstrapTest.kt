// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.BouncyCastleKeyUnlock
import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.proton.api.addresses.AddressDto
import io.pcontacts.core.proton.api.addresses.AddressKeyDto
import io.pcontacts.core.proton.api.addresses.GetAddressesResponse
import io.pcontacts.core.proton.api.addresses.ProtonAddressesApi
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
import org.junit.Assert.assertFalse
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
        val processor = ContactDecryptBootstrap.createProcessor(
            secretStore,
            usersApi,
            FakeAddressesApi.empty(),
            openPgp
        )
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
            ContactDecryptBootstrap.createProcessor(secretStore, usersApi, FakeAddressesApi.empty(), openPgp)
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
            ContactDecryptBootstrap.createProcessor(secretStore, usersApi, FakeAddressesApi.empty(), openPgp)
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
            ContactDecryptBootstrap.createProcessor(secretStore, usersApi, FakeAddressesApi.empty(), openPgp)
            fail("expected DecryptUnavailableException(KEY_UNLOCK_FAILED)")
        } catch (ex: DecryptUnavailableException) {
            assertEquals("KEY_UNLOCK_FAILED", ex.message)
            assertNotNull(ex.cause)
        }
    }

    @Test fun decrypts_card_encrypted_only_to_address_key_end_to_end() = runTest {
        // Regression for the field bug: a real Proton mailbox encrypts
        // contacts to ADDRESS keys, not user keys. Before Phase 11 the
        // decrypt path passed only the user-key ring; decryptToBytes
        // raised "no encrypted data block for any of our N key(s)".
        val userPassphrase = "user-P4ss-correct".toCharArray()
        val (userArmored, userUnlocked) = TestKeys.armoredAndUnlocked(userPassphrase)

        // Generate a fresh address key with a random passphrase, encrypt
        // that passphrase to the user public to form the Token, then
        // encrypt the contact card to the address public ONLY.
        val addressPassphrase = "addr-secret-T0ken-passphrase"
        val addressArmored = TestKeys.armoredKey(addressPassphrase.toCharArray())
        val addressUnlocked = BouncyCastleKeyUnlock.unlock(addressArmored, addressPassphrase.toCharArray())

        val tokenArmored = openPgp.encryptAndSignDetached(
            plaintext = addressPassphrase.toByteArray(Charsets.US_ASCII),
            encryptionKeys = listOf(userUnlocked.public),
            signingKey = userUnlocked.private
        ).armoredMessage

        val signedPlaintext = """
            BEGIN:VCARD
            VERSION:4.0
            UID:urn:uuid:addr-key-contact
            FN:Bob Address
            END:VCARD
        """.trimIndent()
        val signedCard = ContactCardDto(
            type = 2,
            data = signedPlaintext,
            signature = openPgp.signDetached(signedPlaintext.toByteArray(Charsets.UTF_8), userUnlocked.private)
        )

        val encryptedPlaintext = """
            BEGIN:VCARD
            VERSION:4.0
            EMAIL;TYPE=work:bob@proton.me
            END:VCARD
        """.trimIndent()
        val encrypted = openPgp.encryptAndSignDetached(
            plaintext = encryptedPlaintext.toByteArray(Charsets.UTF_8),
            encryptionKeys = listOf(addressUnlocked.public),     // address-only recipient
            signingKey = addressUnlocked.private
        )
        val encryptedCard = ContactCardDto(
            type = 3,
            data = encrypted.armoredMessage,
            signature = encrypted.armoredDetachedSignature
        )
        val contact = ContactDto(id = "c-addr", cards = listOf(signedCard, encryptedCard))

        val secretStore = InMemorySecretStore().apply {
            setKeyPassword(String(userPassphrase).toByteArray(Charsets.UTF_8))
        }
        val usersApi = FakeUsersApi(armoredPrivateKey = userArmored)
        val addressesApi = FakeAddressesApi(
            keys = listOf(
                AddressKeyDto(
                    id = "akey-1",
                    addressId = "addr-1",
                    primary = 1,
                    active = 1,
                    privateKey = addressArmored,
                    token = tokenArmored,
                    signature = null
                )
            )
        )

        val processor = ContactDecryptBootstrap.createProcessor(secretStore, usersApi, addressesApi, openPgp)
        val out = processor.process(contact)

        assertEquals("Bob Address", out.fullName)
        assertEquals(setOf("bob@proton.me"), out.emails.map { it.address }.toSet())
        assertEquals(2, out.cardCount)
    }

    @Test fun address_key_with_undecryptable_token_is_skipped_and_user_key_decrypt_still_works() = runTest {
        val userPassphrase = "user-P4ss-correct".toCharArray()
        val (userArmored, userUnlocked) = TestKeys.armoredAndUnlocked(userPassphrase)

        // Encrypt the contact to the USER public (so user-key path still works).
        val plaintext = """
            BEGIN:VCARD
            VERSION:4.0
            UID:urn:uuid:user-key-contact
            FN:Carol User
            END:VCARD
        """.trimIndent()
        val signed = ContactCardDto(
            type = 2,
            data = plaintext,
            signature = openPgp.signDetached(plaintext.toByteArray(Charsets.UTF_8), userUnlocked.private)
        )
        val encrypted = openPgp.encryptAndSignDetached(
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            encryptionKeys = listOf(userUnlocked.public),
            signingKey = userUnlocked.private
        )
        val encryptedCard = ContactCardDto(
            type = 3,
            data = encrypted.armoredMessage,
            signature = encrypted.armoredDetachedSignature
        )
        val contact = ContactDto(id = "c-1", cards = listOf(signed, encryptedCard))

        // Token armor that cannot be decrypted: encrypt the passphrase
        // to a fresh, throwaway user key the bootstrap will never see.
        val foreignPassphrase = "foreign-P4ss".toCharArray()
        val (_, foreignUnlocked) = TestKeys.armoredAndUnlocked(foreignPassphrase)
        val badTokenArmored = openPgp.encryptAndSignDetached(
            plaintext = "doesnt-matter".toByteArray(Charsets.US_ASCII),
            encryptionKeys = listOf(foreignUnlocked.public),
            signingKey = foreignUnlocked.private
        ).armoredMessage
        val addressArmored = TestKeys.armoredKey("addr-secret".toCharArray())

        val secretStore = InMemorySecretStore().apply {
            setKeyPassword(String(userPassphrase).toByteArray(Charsets.UTF_8))
        }
        val usersApi = FakeUsersApi(armoredPrivateKey = userArmored)
        val addressesApi = FakeAddressesApi(
            keys = listOf(
                AddressKeyDto(
                    id = "akey-bad",
                    addressId = "addr-1",
                    primary = 1,
                    active = 1,
                    privateKey = addressArmored,
                    token = badTokenArmored,
                    signature = null
                )
            )
        )

        // Skip-and-continue: the address key drops out silently, user
        // key decrypt still produces a full DecryptedContact.
        val processor = ContactDecryptBootstrap.createProcessor(
            secretStore,
            usersApi,
            addressesApi,
            openPgp
        )
        val out = processor.process(contact)
        assertEquals("Carol User", out.fullName)
    }

    @Test fun legacy_v1_address_key_with_null_token_unlocks_with_user_keyPassword() = runTest {
        val userPassphrase = "user-P4ss-correct".toCharArray()
        val (userArmored, userUnlocked) = TestKeys.armoredAndUnlocked(userPassphrase)

        // Legacy v1 address keys are unlocked with the user keyPassword
        // directly (see WebClients getDecryptedAddressKeys.ts
        // hasMigratedKeys=false branch). Build one armored under the
        // SAME passphrase as the user key.
        val legacyAddressArmored = TestKeys.armoredKey(userPassphrase.copyOf())
        val legacyAddressUnlocked = BouncyCastleKeyUnlock.unlock(legacyAddressArmored, userPassphrase.copyOf())

        val plaintext = """
            BEGIN:VCARD
            VERSION:4.0
            UID:urn:uuid:legacy
            FN:Dave Legacy
            END:VCARD
        """.trimIndent()
        val signed = ContactCardDto(
            type = 2,
            data = plaintext,
            signature = openPgp.signDetached(plaintext.toByteArray(Charsets.UTF_8), userUnlocked.private)
        )
        val encrypted = openPgp.encryptAndSignDetached(
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            encryptionKeys = listOf(legacyAddressUnlocked.public),    // address-only
            signingKey = legacyAddressUnlocked.private
        )
        val encryptedCard = ContactCardDto(
            type = 3,
            data = encrypted.armoredMessage,
            signature = encrypted.armoredDetachedSignature
        )
        val contact = ContactDto(id = "c-legacy", cards = listOf(signed, encryptedCard))

        val secretStore = InMemorySecretStore().apply {
            setKeyPassword(String(userPassphrase).toByteArray(Charsets.UTF_8))
        }
        val usersApi = FakeUsersApi(armoredPrivateKey = userArmored)
        val addressesApi = FakeAddressesApi(
            keys = listOf(
                AddressKeyDto(
                    id = "akey-legacy",
                    addressId = "addr-1",
                    primary = 1,
                    active = 1,
                    privateKey = legacyAddressArmored,
                    token = null,        // v1
                    signature = null
                )
            )
        )

        val processor = ContactDecryptBootstrap.createProcessor(secretStore, usersApi, addressesApi, openPgp)
        val out = processor.process(contact)
        assertEquals("Dave Legacy", out.fullName)
        // Signature on the E&S card was made by the address key but our
        // verificationKeys union includes the address public, so the
        // card MUST verify.
        assertTrue("E&S card signed by address key must verify", out.verified)
        assertFalse("nothing should be unverified", out.unverifiedCardCount > 0)
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

    private class FakeAddressesApi(
        private val keys: List<AddressKeyDto>
    ) : ProtonAddressesApi {
        override suspend fun getAddresses(): GetAddressesResponse = GetAddressesResponse(
            code = 1000,
            addresses = if (keys.isEmpty()) {
                emptyList()
            } else {
                listOf(AddressDto(id = "addr-1", email = "test@proton.me", keys = keys))
            }
        )
        companion object {
            fun empty(): FakeAddressesApi = FakeAddressesApi(keys = emptyList())
        }
    }
}
