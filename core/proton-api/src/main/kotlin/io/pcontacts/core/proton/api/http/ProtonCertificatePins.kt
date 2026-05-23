// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import okhttp3.CertificatePinner

/**
 * Builds the OkHttp `CertificatePinner` for `*.proton.me` from a
 * resource-driven SPKI pin set. The resource lives at
 * `core/proton-api/src/main/resources/proton_certificate_pins.txt`;
 * format is one `sha256/<base64-spki>` pin per line, blanks +
 * `#`-comments allowed.
 *
 * The resource is intentionally absent in source control until the
 * user supplies real pins from a verified Proton-controlled source
 * (their key transparency log, their public security docs, or
 * pinning them directly from a known-good cert chain). When the
 * resource is missing or empty, the returned pinner adds no
 * constraints for `api.proton.me` — same behaviour as the previous
 * commit (no pinning) but documented in one place.
 *
 * The README at the resource path explains the source-and-pin
 * procedure and the production-gating flip (refuse to build with
 * an empty pin set, once the pins land).
 */
object ProtonCertificatePins {

    const val PROTON_HOST = "api.proton.me"
    const val RESOURCE_PATH = "/proton_certificate_pins.txt"

    /**
     * Reads pins from the classpath. Returns an empty list if the
     * resource is absent or contains no usable lines (everything
     * commented out / blank).
     */
    fun loadFromClasspath(): List<String> {
        val stream = ProtonCertificatePins::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: return emptyList()
        return stream.use { it.bufferedReader().readLines() }
            .map { it.trim() }
            .filter { line -> line.isNotBlank() && !line.startsWith("#") }
    }

    /**
     * Builds a CertificatePinner. With a non-empty pin list, every
     * TLS handshake to `host` must present a chain whose SPKI matches
     * one of the pins. With an empty list the returned pinner adds
     * no constraints (effectively unpinned).
     */
    fun buildPinner(
        host: String = PROTON_HOST,
        pins: List<String> = loadFromClasspath()
    ): CertificatePinner {
        val builder = CertificatePinner.Builder()
        if (pins.isNotEmpty()) {
            builder.add(host, *pins.toTypedArray())
        }
        return builder.build()
    }
}
