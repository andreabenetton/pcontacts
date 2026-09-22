// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.annotation.StringRes

/**
 * One Android distribution that can run without Google's privileged
 * service stack, as presented on [DeGoogledRomsScreen]. Being listed
 * says nothing about the project's security properties (Verified
 * Boot, update cadence, hardware support): the screen explains those
 * are separate, and the entries do not rank the projects.
 *
 * Names and URLs are not translated; every other attribute is a
 * string resource.
 */
data class DeGoogledRom(
    val name: String,
    @StringRes val googleFreeByDefault: Int,
    @StringRes val googleCompatibility: Int,
    @StringRes val focus: Int,
    @StringRes val hardware: Int,
    val website: String,
    @StringRes val note: Int? = null
)

/** The projects the screen lists, in presentation order. Defined once; rendered by the screen only. */
val DE_GOOGLED_ROMS: List<DeGoogledRom> = listOf(
    DeGoogledRom(
        name = "GrapheneOS",
        googleFreeByDefault = R.string.rom_google_free_yes,
        googleCompatibility = R.string.rom_grapheneos_compat,
        focus = R.string.rom_grapheneos_focus,
        hardware = R.string.rom_grapheneos_hardware,
        website = "https://grapheneos.org/",
        note = R.string.rom_grapheneos_note
    ),
    DeGoogledRom(
        name = "CalyxOS",
        googleFreeByDefault = R.string.rom_google_free_yes,
        googleCompatibility = R.string.rom_calyxos_compat,
        focus = R.string.rom_calyxos_focus,
        hardware = R.string.rom_calyxos_hardware,
        website = "https://calyxos.org/"
    ),
    DeGoogledRom(
        name = "iodéOS",
        googleFreeByDefault = R.string.rom_google_free_yes,
        googleCompatibility = R.string.rom_iodeos_compat,
        focus = R.string.rom_iodeos_focus,
        hardware = R.string.rom_iodeos_hardware,
        website = "https://iode.tech/"
    ),
    DeGoogledRom(
        name = "/e/OS",
        googleFreeByDefault = R.string.rom_google_free_yes,
        googleCompatibility = R.string.rom_eos_compat,
        focus = R.string.rom_eos_focus,
        hardware = R.string.rom_eos_hardware,
        website = "https://e.foundation/"
    ),
    DeGoogledRom(
        name = "LineageOS for microG",
        googleFreeByDefault = R.string.rom_google_free_yes,
        googleCompatibility = R.string.rom_lineage_microg_compat,
        focus = R.string.rom_lineage_microg_focus,
        hardware = R.string.rom_lineage_microg_hardware,
        website = "https://lineage.microg.org/",
        note = R.string.rom_lineage_microg_note
    ),
    DeGoogledRom(
        name = "LineageOS",
        googleFreeByDefault = R.string.rom_lineageos_google_free,
        googleCompatibility = R.string.rom_lineageos_compat,
        focus = R.string.rom_lineageos_focus,
        hardware = R.string.rom_lineageos_hardware,
        website = "https://lineageos.org/",
        note = R.string.rom_lineageos_note
    ),
    DeGoogledRom(
        name = "ShiftOS-L",
        googleFreeByDefault = R.string.rom_google_free_yes,
        googleCompatibility = R.string.rom_shiftos_compat,
        focus = R.string.rom_shiftos_focus,
        hardware = R.string.rom_shiftos_hardware,
        website = "https://www.shift.eco/shiftos/"
    ),
    DeGoogledRom(
        name = "Replicant",
        googleFreeByDefault = R.string.rom_google_free_yes,
        googleCompatibility = R.string.rom_replicant_compat,
        focus = R.string.rom_replicant_focus,
        hardware = R.string.rom_replicant_hardware,
        website = "https://replicant.us/",
        note = R.string.rom_replicant_note
    )
)
