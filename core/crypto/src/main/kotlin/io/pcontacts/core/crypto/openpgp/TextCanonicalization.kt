// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

/**
 * Canonicalization helpers Proton's web client applies before signing
 * vCard card payloads.
 *
 * `stripTrailingSpaces = true` — every line's trailing whitespace is
 * removed. This is `[V]` in `packages/shared/lib/contacts/decrypt.ts`
 * (verifyMessage call) and matches the Proton wire behaviour we observed.
 *
 * Lines are joined with CRLF on output (RFC 4880 canonical text mode).
 * Input may use any combination of CR, LF, or CRLF as line separators.
 */
object TextCanonicalization {

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
    private val WHITESPACE = setOf(' ', '\t')

    /** Returns the canonical-text bytes (CRLF line endings, no trailing whitespace). */
    fun canonicalize(input: ByteArray, stripTrailingSpaces: Boolean = true): ByteArray {
        val text = String(input, Charsets.UTF_8)
        val lines = splitLines(text)
        val processed = if (stripTrailingSpaces) lines.map { it.trimEnd { c -> c in WHITESPACE } } else lines
        return processed.joinToString("\r\n").toByteArray(Charsets.UTF_8)
    }

    private fun splitLines(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                out += text.substring(start, i)
                start = i + 1
                i = start
            } else if (c == '\r') {
                out += text.substring(start, i)
                start = if (i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                i = start
            } else {
                i++
            }
        }
        // Trailing content after the last separator (or a single line w/ no separator).
        if (start <= text.length) out += text.substring(start)
        return out
    }
}
