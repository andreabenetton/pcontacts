// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.srp

import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.crypto.openpgp.TestKeyGen
import java.io.ByteArrayOutputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonModulusVerifierTest {

    private val openPgp = BouncyCastleOpenPgpService()

    @Test fun valid_signature_against_pinned_key_returns_VALID() {
        val keys = TestKeyGen.rsa2048()
        val cleartext = "AAECAwQFBgcICQoLDA0ODw=="
        val signature = openPgp.signDetached(
            plaintext = cleartext.toByteArray(Charsets.US_ASCII),
            signingKey = keys.priv
        )
        val verifier = BouncyCastleProtonModulusVerifier(
            pinnedPublicKeyArmored = armoredPublicKeyOf(keys),
            openPgp = openPgp
        )

        assertEquals(ProtonModulusVerification.VALID, verifier.verify(cleartext, signature))
    }

    @Test fun tampered_signature_returns_INVALID() {
        val keys = TestKeyGen.rsa2048()
        val cleartext = "AAECAwQFBgcICQoLDA0ODw=="
        val signature = openPgp.signDetached(
            plaintext = cleartext.toByteArray(Charsets.US_ASCII),
            signingKey = keys.priv
        )
        val verifier = BouncyCastleProtonModulusVerifier(
            pinnedPublicKeyArmored = armoredPublicKeyOf(keys),
            openPgp = openPgp
        )

        // Feed a DIFFERENT cleartext — signature can't possibly verify against it.
        val tamperedCleartext = "QUFFQ0F3UUZCZ2NJQ1FvTERBME9Edz09"
        assertEquals(ProtonModulusVerification.INVALID, verifier.verify(tamperedCleartext, signature))
    }

    @Test fun signature_from_a_different_key_returns_INVALID() {
        val pinnedKeys = TestKeyGen.rsa2048()
        val attackerKeys = TestKeyGen.rsa2048(identity = "attacker")
        val cleartext = "AAECAwQFBgcICQoLDA0ODw=="
        val attackerSig = openPgp.signDetached(
            plaintext = cleartext.toByteArray(Charsets.US_ASCII),
            signingKey = attackerKeys.priv
        )
        val verifier = BouncyCastleProtonModulusVerifier(
            pinnedPublicKeyArmored = armoredPublicKeyOf(pinnedKeys),
            openPgp = openPgp
        )

        assertEquals(ProtonModulusVerification.INVALID, verifier.verify(cleartext, attackerSig))
    }

    @Test fun missing_pinned_key_returns_NO_SIGNER_KEY() {
        val verifier = BouncyCastleProtonModulusVerifier(
            pinnedPublicKeyArmored = null,
            openPgp = openPgp
        )
        assertEquals(
            ProtonModulusVerification.NO_SIGNER_KEY,
            verifier.verify("anything", "any signature")
        )
    }

    @Test fun malformed_pinned_key_falls_back_to_NO_SIGNER_KEY() {
        val verifier = BouncyCastleProtonModulusVerifier(
            pinnedPublicKeyArmored = "this is not a PGP key block",
            openPgp = openPgp
        )
        assertEquals(
            ProtonModulusVerification.NO_SIGNER_KEY,
            verifier.verify("anything", "any signature")
        )
    }

    @Test fun loadPinnedKeyFromClasspath_returns_null_when_resource_absent() {
        // No resource file is committed at /proton_srp_signing_key.asc;
        // README documents that the real key lands in a follow-up commit.
        assertEquals(null, BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath())
    }

    private fun armoredPublicKeyOf(testKey: TestKeyGen.TestKey): String {
        // PgpPublicKeyHandle.raw is internal — same module, so the test
        // accesses it directly without reflection.
        val ring = PGPPublicKeyRing(listOf(testKey.pub.raw))
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { ring.encode(it) }
        return out.toString(Charsets.US_ASCII)
    }
}
