// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtonHostDnsGuardTest {

    private val fakeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    }

    @Test fun proton_me_root_host_is_allowed() {
        ProtonHostDnsGuard(fakeDns).lookup("proton.me")
    }

    @Test fun api_proton_me_subdomain_is_allowed() {
        ProtonHostDnsGuard(fakeDns).lookup("api.proton.me")
    }

    @Test fun arbitrary_proton_me_subdomain_is_allowed() {
        ProtonHostDnsGuard(fakeDns).lookup("contacts.proton.me")
        ProtonHostDnsGuard(fakeDns).lookup("mail.proton.me")
    }

    @Test fun localhost_is_allowed_so_MockWebServer_tests_can_share_the_client() {
        ProtonHostDnsGuard(fakeDns).lookup("localhost")
        ProtonHostDnsGuard(fakeDns).lookup("127.0.0.1")
    }

    @Test fun unrelated_host_is_rejected_with_UnknownHostException() {
        val ex = assertThrows(UnknownHostException::class.java) {
            ProtonHostDnsGuard(fakeDns).lookup("example.com")
        }
        assertEquals(true, ex.message!!.contains("refused host"))
    }

    @Test fun lookalike_proton_me_in_a_subdomain_position_is_rejected() {
        // "proton.me.attacker.com" must NOT be allowed even though
        // it contains "proton.me".
        assertThrows(UnknownHostException::class.java) {
            ProtonHostDnsGuard(fakeDns).lookup("proton.me.attacker.com")
        }
    }

    @Test fun host_match_is_case_insensitive() {
        ProtonHostDnsGuard(fakeDns).lookup("API.PROTON.ME")
        ProtonHostDnsGuard(fakeDns).lookup("Api.Proton.Me")
    }
}
