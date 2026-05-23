// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * DNS resolver that rejects hosts outside `*.proton.me`. Enforces
 * the CLAUDE.md anti-pattern "no network call to a host not
 * matching *.proton.me from :core:proton-api" mechanically — a
 * bug that tries to call any other host throws
 * UnknownHostException at the DNS hop.
 *
 * `localhost` and `127.0.0.1` are allowed so MockWebServer-backed
 * tests can drive the same OkHttpClient without spinning up a
 * second, less-guarded one.
 */
internal class ProtonHostDnsGuard(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!isAllowed(hostname)) {
            throw UnknownHostException("ProtonHostDnsGuard refused host: $hostname")
        }
        return delegate.lookup(hostname)
    }

    private fun isAllowed(hostname: String): Boolean {
        val host = hostname.lowercase()
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host == "proton.me" ||
            host.endsWith(".proton.me")
    }
}
