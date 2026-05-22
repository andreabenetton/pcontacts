// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

/**
 * Pure-function redactor applied to every rendered log message before the
 * sink sees it. Covers the patterns ADR-0015 enumerates: bearer tokens,
 * JSON fields named like Proton secrets, common credential keywords with
 * trailing values, and inline PGP armored blocks.
 *
 * Throwables are reduced to a class name + first in-project stack frame.
 * Exception messages are NEVER surfaced — they too often carry payload bytes.
 */
object Redactor {

    private val bearerPattern = Regex(
        "(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]+"
    )

    private val jsonFieldPattern = Regex(
        "(?i)(\"(?:AccessToken|RefreshToken|PrivateKey|Data|Signature|" +
            "password|passphrase|token|keyPassword|userKey|addressKey|" +
            "ClientProof|ClientEphemeral|ServerProof|TwoFactorCode)\"" +
            "\\s*:\\s*)\"[^\"]*\""
    )

    private val keywordPattern = Regex(
        "(?i)((?:password|passphrase|token|private[-_]?key|signature|" +
            "secret|api[-_]?key)[\\s:=]+)\\S+"
    )

    private val pgpBlockPattern = Regex(
        "-----BEGIN PGP [A-Z ]+-----[\\s\\S]*?-----END PGP [A-Z ]+-----"
    )

    fun redact(message: String): String {
        var out = message
        out = bearerPattern.replace(out) { it.groupValues[1] + "<redacted>" }
        out = jsonFieldPattern.replace(out) { it.groupValues[1] + "\"<redacted>\"" }
        out = keywordPattern.replace(out) { it.groupValues[1] + "<redacted>" }
        out = pgpBlockPattern.replace(out, "<redacted pgp block>")
        return out
    }

    /**
     * Reduce a throwable to a non-sensitive fingerprint:
     *   `class.name @ first-in-project-frame#method:line`
     * The throwable's own message is intentionally dropped — it may contain
     * payload bytes (e.g. an HTTP body, a vCard fragment).
     */
    fun redactThrowable(t: Throwable): String {
        val frame = t.stackTrace.firstOrNull { it.className.startsWith("io.pcontacts.") }
            ?: t.stackTrace.firstOrNull()
        val location = frame?.let { "${it.className}#${it.methodName}:${it.lineNumber}" } ?: "<no-frame>"
        return "${t.javaClass.name}@$location"
    }
}
