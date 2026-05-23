// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.srp

/**
 * Decoder for the OpenPGP cleartext-signed envelope Proton wraps the
 * SRP `Modulus` field in (`auth/info` response). Plan §2.7 step 2 +
 * ADR-0014:
 *
 *   -----BEGIN PGP SIGNED MESSAGE-----
 *   Hash: SHA512
 *
 *   <base64 modulus>
 *   -----BEGIN PGP SIGNATURE-----
 *
 *   <armored signature>
 *   -----END PGP SIGNATURE-----
 *
 * The cleartext payload is the modulus base64 the SRP client wants;
 * the detached signature is what an ADR-0014 verifier would check
 * against the pinned Proton SRP signing public key. This decoder
 * extracts both halves but does NOT verify — that's the (deferred)
 * ADR-0014 work that ships when the pinned key is available.
 *
 * Tolerates plain base64 input too (raw modulus, no envelope) — the
 * orchestrator can call `decode()` unconditionally and the test
 * surface stays the same regardless of which form the server sends.
 *
 * Verification markers:
 *   [V] envelope shape — confirmed in
 *       packages/shared/lib/srp.ts + the OpenPGP cleartext signature spec.
 *   [A] absence of envelope → raw base64 is a fallback for older API
 *       versions / test fixtures; real prod always envelopes.
 */
object ProtonModulusEnvelope {

    /**
     * @param cleartextBase64  the modulus's base64 representation, suitable
     *                         for direct `Base64.getDecoder().decode(...)`.
     * @param armoredSignature the `-----BEGIN PGP SIGNATURE-----` block, or
     *                         null if the input wasn't an envelope.
     *                         An ADR-0014 verifier consumes this.
     */
    data class Decoded(
        val cleartextBase64: String,
        val armoredSignature: String?
    )

    fun decode(serverValue: String): Decoded {
        val trimmed = serverValue.trim()
        if (!trimmed.startsWith(BEGIN_MESSAGE)) {
            // Raw base64 — no envelope to peel off.
            return Decoded(cleartextBase64 = trimmed, armoredSignature = null)
        }

        // OpenPGP cleartext envelope. The cleartext section runs from after
        // the blank line that terminates the armor headers up to the
        // `-----BEGIN PGP SIGNATURE-----` marker. The signature section
        // runs from there through `-----END PGP SIGNATURE-----`.
        val sigStart = trimmed.indexOf(BEGIN_SIG).takeIf { it >= 0 }
            ?: error("modulus envelope missing PGP SIGNATURE block")
        val sigEnd = trimmed.indexOf(END_SIG, startIndex = sigStart).takeIf { it >= 0 }
            ?: error("modulus envelope missing PGP SIGNATURE end marker")

        val headerEnd = findBodyStart(trimmed.substring(0, sigStart))
            ?: error("modulus envelope missing blank-line separator between headers and body")

        val rawBody = trimmed.substring(headerEnd, sigStart)
        val cleartext = canonicalizeCleartext(rawBody)
        val armoredSignature = trimmed.substring(sigStart, sigEnd + END_SIG.length)

        return Decoded(cleartextBase64 = cleartext, armoredSignature = armoredSignature)
    }

    /**
     * Walks the armor-header region looking for the blank line that
     * separates headers from body. Returns the index *after* the blank
     * line, i.e. the start of the body.
     */
    private fun findBodyStart(armoredHeaderRegion: String): Int? {
        // Per RFC 4880 §7, the cleartext body starts after the blank line
        // that follows the armor headers. The headers themselves begin
        // after the `BEGIN PGP SIGNED MESSAGE` marker line.
        val beginIdx = armoredHeaderRegion.indexOf(BEGIN_MESSAGE)
        if (beginIdx < 0) return null
        // Find the next blank line (\n\n or \r\n\r\n) after the BEGIN marker.
        val rest = armoredHeaderRegion.substring(beginIdx)
        val blankCrLf = rest.indexOf("\r\n\r\n").takeIf { it >= 0 }
        val blankLf = rest.indexOf("\n\n").takeIf { it >= 0 }
        val (rel, sepLen) = when {
            blankCrLf != null && (blankLf == null || blankCrLf <= blankLf) -> blankCrLf to 4
            blankLf != null -> blankLf to 2
            else -> return null
        }
        return beginIdx + rel + sepLen
    }

    /**
     * Canonicalizes the cleartext per OpenPGP rules: normalize line
     * endings to LF, strip trailing whitespace from each line, drop
     * leading/trailing blank lines. For a single-line base64 modulus
     * this collapses to "give me back the body, stripped".
     */
    private fun canonicalizeCleartext(rawBody: String): String =
        rawBody.lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .trim()

    private const val BEGIN_MESSAGE = "-----BEGIN PGP SIGNED MESSAGE-----"
    private const val BEGIN_SIG = "-----BEGIN PGP SIGNATURE-----"
    private const val END_SIG = "-----END PGP SIGNATURE-----"
}
