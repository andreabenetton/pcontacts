// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test fun redacts_bearer_token() {
        val out = Redactor.redact("Authorization: Bearer abc.def.ghi-jkl_mno=")
        assertEquals("Authorization: Bearer <redacted>", out)
    }

    @Test fun redacts_json_AccessToken_field() {
        val out = Redactor.redact("""{"UID":"abc","AccessToken":"secret-stuff","UserID":"xyz"}""")
        assertEquals("""{"UID":"abc","AccessToken":"<redacted>","UserID":"xyz"}""", out)
    }

    @Test fun redacts_multiple_proton_secret_fields() {
        val input = """{"AccessToken":"a","RefreshToken":"b","PrivateKey":"c","Signature":"d","Data":"e"}"""
        val out = Redactor.redact(input)
        assertFalse("AccessToken value leaked", out.contains("\"a\""))
        assertFalse("RefreshToken value leaked", out.contains("\"b\""))
        assertFalse("PrivateKey value leaked", out.contains("\"c\""))
        assertFalse("Signature value leaked", out.contains("\"d\""))
        assertFalse("Data value leaked", out.contains("\"e\""))
        assertEquals(5, "<redacted>".toRegex().findAll(out).count())
    }

    @Test fun redacts_keyword_password_token_values() {
        val out = Redactor.redact("password=hunter2 token: deadbeef passphrase = correct-horse-battery-staple")
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("deadbeef"))
        assertFalse(out.contains("correct-horse"))
    }

    @Test fun redacts_inline_pgp_block() {
        val pgp = """
            -----BEGIN PGP MESSAGE-----
            Version: GnuPG v2

            hQEMA8aZ... payload ...===
            -----END PGP MESSAGE-----
        """.trimIndent()
        val out = Redactor.redact("contact card: $pgp end")
        assertTrue(out.contains("<redacted pgp block>"))
        assertFalse(out.contains("hQEMA8aZ"))
    }

    @Test fun preserves_safe_content() {
        val safe = "sync completed contacts=42 elapsed=350ms"
        assertEquals(safe, Redactor.redact(safe))
    }

    @Test fun throwable_redaction_drops_message() {
        val t = IllegalStateException("THIS_MESSAGE_MUST_NOT_APPEAR_IN_LOGS")
        val out = Redactor.redactThrowable(t)
        assertFalse(out.contains("THIS_MESSAGE_MUST_NOT_APPEAR_IN_LOGS"))
        assertTrue(out.startsWith("java.lang.IllegalStateException@"))
    }

    @Test fun throwable_redaction_prefers_in_project_frame() {
        val t = try {
            triggerInProject()
        } catch (e: IllegalStateException) {
            e
        }
        val out = Redactor.redactThrowable(t)
        assertTrue("location should be in io.pcontacts.*, was: $out", out.contains("io.pcontacts."))
    }

    private fun triggerInProject(): Nothing = throw IllegalStateException("X")
}
