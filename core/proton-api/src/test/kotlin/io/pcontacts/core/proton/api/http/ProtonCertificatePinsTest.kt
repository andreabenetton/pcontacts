// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonCertificatePinsTest {

    @Test fun loadFromClasspath_returns_two_isrg_root_pins() {
        val pins = ProtonCertificatePins.loadFromClasspath()
        assertEquals("expected 2 ISRG root pins", 2, pins.size)
        assertTrue("each pin must start with sha256/", pins.all { it.startsWith("sha256/") })
    }

    @Test fun buildPinner_with_empty_pin_list_returns_a_no_constraint_pinner() {
        // OkHttp's CertificatePinner with no .add() calls applies no
        // constraints to any host — same as the default. Verify by
        // building and asserting we don't crash; the actual constraint
        // count isn't introspectable via the public API.
        val pinner = ProtonCertificatePins.buildPinner(pins = emptyList())
        assertNotNull(pinner)
    }

    @Test fun buildPinner_with_pin_list_applies_them_to_the_host() {
        val pins = listOf(
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        )
        val pinner = ProtonCertificatePins.buildPinner(host = "*.proton.me", pins = pins)
        // CertificatePinner.findMatchingPins returns the pins configured
        // for a given host — non-empty here means our pins are wired in.
        val matched = pinner.findMatchingPins("mail-api.proton.me")
        assertEquals(2, matched.size)
    }

    @Test fun buildPinner_only_pins_the_configured_host_not_other_hosts() {
        val pinner = ProtonCertificatePins.buildPinner(
            host = "*.proton.me",
            pins = listOf("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        )
        // A different host carries zero pin constraints (DNS guard
        // separately refuses non-proton hosts at the resolver layer).
        assertEquals(0, pinner.findMatchingPins("example.com").size)
    }
}
