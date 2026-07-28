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

    private const val MAX_CAUSE_DEPTH = 3
    private const val MAX_FRAMES = 6

    /**
     * Reduce a throwable to a non-sensitive but *diagnosable* fingerprint:
     *   `class@frame <- frame … caused by class@frame …`
     *
     * Includes up to [MAX_FRAMES] in-project frames per throwable — so the
     * real throw site is visible, not just the outermost coroutine rethrow
     * (a `runBlocking` boundary would otherwise collapse everything to the
     * SyncAdapter frame) — and walks up to [MAX_CAUSE_DEPTH] causes. The
     * throwable's own message is intentionally dropped: it may carry payload
     * bytes (an HTTP body, a vCard fragment). Only class names and code
     * frames — never messages — are emitted.
     */
    fun redactThrowable(t: Throwable): String = buildString {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth <= MAX_CAUSE_DEPTH) {
            if (depth > 0) append(" caused by ")
            append(current.javaClass.name)
            append('@')
            append(projectFrames(current))
            val next = current.cause
            current = if (next === current) null else next
            depth += 1
        }
    }

    private fun projectFrames(t: Throwable): String {
        val inProject = t.stackTrace.filter { it.className.startsWith("io.pcontacts.") }
        val frames = inProject.ifEmpty { t.stackTrace.take(1) }.take(MAX_FRAMES)
        if (frames.isEmpty()) return "<no-frame>"
        return frames.joinToString(" <- ") { "${it.className}#${it.methodName}:${it.lineNumber}" }
    }
}
