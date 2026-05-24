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
 * Pinning strategy: ISRG Root X1 (RSA 4096) + ISRG Root X2 (EC
 * P-384). Root-level pins survive leaf and intermediate rotation
 * within the Let's Encrypt / ISRG chain. Captured 2026-05-24 from
 * the official Let's Encrypt PEM endpoints and cross-verified
 * against the live `mail-api.proton.me` cert chain.
 *
 * Release builds gate on this resource being non-empty via the
 * `:core:proton-api:verifyCertificatePins` task (wired as a
 * dependency of `:app:assembleRelease`). If the resource is
 * accidentally deleted, the release build fails.
 */
object ProtonCertificatePins {

    const val PROTON_HOST = "*.proton.me"
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
