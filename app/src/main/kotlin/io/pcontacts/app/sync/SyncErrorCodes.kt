// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import androidx.annotation.StringRes
import io.pcontacts.app.R

/**
 * Stable, non-sensitive codes for the last sync failure, persisted in
 * UserPreferences by [ProtonSyncAdapter] and mapped to a user-facing
 * string via [messageRes]. Values are persisted, so do not rename them.
 */
internal object SyncErrorCodes {
    const val REAUTH = "reauth"
    const val VERIFICATION = "verification"
    const val APP_VERSION = "app_version"

    /** A genuine network/IO error — the connection really is the problem. */
    const val NETWORK = "network"

    /** Any other failure (a bug, bad data). NOT a connectivity problem. */
    const val GENERIC = "generic"

    @StringRes
    fun messageRes(code: String?): Int = when (code) {
        REAUTH -> R.string.launcher_sync_failed_reauth
        VERIFICATION -> R.string.launcher_sync_failed_verification
        APP_VERSION -> R.string.launcher_sync_failed_app_version
        NETWORK -> R.string.launcher_sync_failed_network
        else -> R.string.launcher_sync_failed_generic
    }
}
