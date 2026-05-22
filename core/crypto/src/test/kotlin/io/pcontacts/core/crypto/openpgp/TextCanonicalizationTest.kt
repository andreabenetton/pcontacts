// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCanonicalizationTest {

    private fun canon(s: String, stripTrailingSpaces: Boolean = true): String =
        String(TextCanonicalization.canonicalize(s.toByteArray(Charsets.UTF_8), stripTrailingSpaces))

    @Test fun normalizes_lf_to_crlf() {
        assertEquals("a\r\nb\r\nc", canon("a\nb\nc"))
    }

    @Test fun normalizes_cr_to_crlf() {
        assertEquals("a\r\nb\r\nc", canon("a\rb\rc"))
    }

    @Test fun preserves_crlf() {
        assertEquals("a\r\nb\r\nc", canon("a\r\nb\r\nc"))
    }

    @Test fun mixed_separators_normalize_to_crlf() {
        assertEquals("a\r\nb\r\nc\r\nd", canon("a\rb\nc\r\nd"))
    }

    @Test fun strips_trailing_spaces_per_line_when_enabled() {
        assertEquals("a\r\nb\r\nc", canon("a   \nb\t\t\nc  "))
    }

    @Test fun preserves_trailing_spaces_when_disabled() {
        assertEquals("a   \r\nb\t\t\r\nc  ", canon("a   \nb\t\t\nc  ", stripTrailingSpaces = false))
    }

    @Test fun leaves_intra_line_whitespace_intact() {
        assertEquals("a   b\r\nc d", canon("a   b   \nc d"))
    }

    @Test fun blank_lines_round_trip() {
        assertEquals("a\r\n\r\nb", canon("a\n\nb"))
    }

    @Test fun handles_trailing_newline() {
        assertEquals("a\r\n", canon("a\n"))
    }

    @Test fun empty_input_yields_empty_output() {
        assertEquals("", canon(""))
    }
}
