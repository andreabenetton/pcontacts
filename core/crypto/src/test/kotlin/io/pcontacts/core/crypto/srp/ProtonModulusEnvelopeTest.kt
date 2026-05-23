// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.srp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonModulusEnvelopeTest {

    @Test fun raw_base64_passes_through_unchanged_with_null_signature() {
        val raw = "AAECAwQFBgcICQoLDA0ODw=="
        val decoded = ProtonModulusEnvelope.decode(raw)
        assertEquals(raw, decoded.cleartextBase64)
        assertNull(decoded.armoredSignature)
    }

    @Test fun envelope_with_lf_line_endings_extracts_base64_and_signature() {
        val envelope = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA512

            AAECAwQFBgcICQoLDA0ODw==
            -----BEGIN PGP SIGNATURE-----

            iQEzBAEBCgAdFiEE...truncated...
            =abcd
            -----END PGP SIGNATURE-----
        """.trimIndent()

        val decoded = ProtonModulusEnvelope.decode(envelope)

        assertEquals("AAECAwQFBgcICQoLDA0ODw==", decoded.cleartextBase64)
        assertNotNull(decoded.armoredSignature)
        assertTrue(decoded.armoredSignature!!.startsWith("-----BEGIN PGP SIGNATURE-----"))
        assertTrue(decoded.armoredSignature!!.endsWith("-----END PGP SIGNATURE-----"))
    }

    @Test fun envelope_with_crlf_line_endings_extracts_base64_correctly() {
        val envelope = "-----BEGIN PGP SIGNED MESSAGE-----\r\n" +
            "Hash: SHA512\r\n" +
            "\r\n" +
            "AAECAwQFBg==\r\n" +
            "-----BEGIN PGP SIGNATURE-----\r\n" +
            "\r\n" +
            "iQEzBAEBCgAd\r\n" +
            "=abcd\r\n" +
            "-----END PGP SIGNATURE-----\r\n"

        val decoded = ProtonModulusEnvelope.decode(envelope)
        assertEquals("AAECAwQFBg==", decoded.cleartextBase64)
        assertNotNull(decoded.armoredSignature)
    }

    @Test fun envelope_trims_leading_and_trailing_whitespace_in_serverValue() {
        val envelope = "\n\n  -----BEGIN PGP SIGNED MESSAGE-----\n" +
            "Hash: SHA512\n\nAAECAwQFBg==\n" +
            "-----BEGIN PGP SIGNATURE-----\n\niQEz\n=ab\n-----END PGP SIGNATURE-----\n\n"
        // Note: leading whitespace before BEGIN marker isn't standard;
        // we trim() the whole input first so this still works.
        val decoded = ProtonModulusEnvelope.decode(envelope)
        assertEquals("AAECAwQFBg==", decoded.cleartextBase64)
    }

    @Test fun envelope_missing_signature_block_throws_with_actionable_message() {
        val malformed = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA512

            AAECAwQFBg==
        """.trimIndent()

        val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ProtonModulusEnvelope.decode(malformed)
        }
        assertTrue(ex.message!!.contains("SIGNATURE"))
    }

    @Test fun envelope_with_multiple_header_lines_still_extracts_body() {
        val envelope = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA512
            Version: GnuPG v2

            AAECAwQFBg==
            -----BEGIN PGP SIGNATURE-----

            iQEz
            =ab
            -----END PGP SIGNATURE-----
        """.trimIndent()
        val decoded = ProtonModulusEnvelope.decode(envelope)
        assertEquals("AAECAwQFBg==", decoded.cleartextBase64)
    }
}
