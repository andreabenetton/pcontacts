// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

/** The periodic sync cadences the user can pick; the screen renders the hours as localised text. */
enum class SyncInterval(val hours: Long) {
    ONE_HOUR(1),
    SIX_HOURS(6),
    TWELVE_HOURS(12),
    TWENTY_FOUR_HOURS(24);

    companion object {
        fun fromHours(hours: Long): SyncInterval =
            entries.firstOrNull { it.hours == hours } ?: TWELVE_HOURS
    }
}
