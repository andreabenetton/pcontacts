// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.advisories

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS resolver that resolves `api.osv.dev` and nothing else (ADR-0025), the
 * counterpart of the Proton client's guard: a bug that tries to reach any
 * other host from this client fails at the DNS hop. `localhost` and
 * `127.0.0.1` are allowed for MockWebServer-backed tests.
 */
internal class OsvHostDnsGuard(private val delegate: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (!isAllowed(hostname)) throw UnknownHostException("OsvHostDnsGuard refused host: $hostname")
        return delegate.lookup(hostname)
    }

    private fun isAllowed(hostname: String): Boolean {
        val host = hostname.lowercase()
        return host == OSV_HOST || host == "localhost" || host == "127.0.0.1"
    }

    companion object {
        const val OSV_HOST = "api.osv.dev"
    }
}
