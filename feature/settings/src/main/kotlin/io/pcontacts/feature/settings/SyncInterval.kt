// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

enum class SyncInterval(val hours: Long, val label: String) {
    ONE_HOUR(1, "1 hour"),
    SIX_HOURS(6, "6 hours"),
    TWELVE_HOURS(12, "12 hours"),
    TWENTY_FOUR_HOURS(24, "24 hours");

    companion object {
        fun fromHours(hours: Long): SyncInterval =
            entries.firstOrNull { it.hours == hours } ?: TWELVE_HOURS
    }
}
