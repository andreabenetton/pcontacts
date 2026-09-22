// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

/**
 * The positions of the sync slider. [OFF] is Android's own per-account sync switch, driven
 * from here; every other position is a cadence and switches the account back on.
 */
enum class SyncInterval(val hours: Long) {
    OFF(0),
    ONE_HOUR(1),
    SIX_HOURS(6),
    TWELVE_HOURS(12),
    TWENTY_FOUR_HOURS(24);

    companion object {
        /** A stored cadence; 0 or an unknown value falls back to twelve hours (OFF is never stored). */
        fun fromHours(hours: Long): SyncInterval =
            entries.firstOrNull { it.hours == hours && it != OFF } ?: TWELVE_HOURS
    }
}

/**
 * Android's two sync switches for the account (ADR-0004): the per-account "Contacts" switch,
 * which the slider's Off position drives, and the phone-wide "Auto-sync data" switch, which
 * the app only reports and points to.
 */
data class SyncSwitchState(val accountOn: Boolean, val masterOn: Boolean) {
    companion object {
        val ON = SyncSwitchState(accountOn = true, masterOn = true)
    }
}
