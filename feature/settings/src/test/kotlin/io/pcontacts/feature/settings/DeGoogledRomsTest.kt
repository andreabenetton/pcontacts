// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeGoogledRomsTest {

    @Test fun lists_the_eight_projects_once_each_in_order() {
        assertEquals(
            listOf(
                "GrapheneOS",
                "CalyxOS",
                "iodéOS",
                "/e/OS",
                "LineageOS for microG",
                "LineageOS",
                "ShiftOS-L",
                "Replicant"
            ),
            DE_GOOGLED_ROMS.map { it.name }
        )
    }

    @Test fun every_website_is_https_on_the_project_domain() {
        val hosts = DE_GOOGLED_ROMS.associate { it.name to it.website.displayHost() }
        assertTrue(DE_GOOGLED_ROMS.all { it.website.startsWith("https://") })
        assertEquals("grapheneos.org", hosts["GrapheneOS"])
        assertEquals("calyxos.org", hosts["CalyxOS"])
        assertEquals("iode.tech", hosts["iodéOS"])
        assertEquals("e.foundation", hosts["/e/OS"])
        assertEquals("lineage.microg.org", hosts["LineageOS for microG"])
        assertEquals("lineageos.org", hosts["LineageOS"])
        assertEquals("shift.eco/shiftos", hosts["ShiftOS-L"])
        assertEquals("replicant.us", hosts["Replicant"])
    }

    @Test fun lineageos_is_the_only_entry_not_google_free_by_default() {
        val conditional = DE_GOOGLED_ROMS.filter { it.googleFreeByDefault != R.string.rom_google_free_yes }
        assertEquals(listOf("LineageOS"), conditional.map { it.name })
    }
}
