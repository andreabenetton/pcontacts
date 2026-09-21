// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import io.pcontacts.feature.settings.ContactsPermissionRoute

/**
 * Deepest reachable page for revoking READ_CONTACTS, in order: the
 * permission controller's per-permission list ("Contacts and
 * accounts"; the `Intent.ACTION_MANAGE_PERMISSION_APPS` contract,
 * guarded by a signature permission on recent Pixels), then Settings'
 * "Privacy controls" (one tap from Permission manager), then the
 * privacy hub.
 */
class ContactsPermissionPage(private val activity: Activity) {

    private val pages: List<Pair<Intent, ContactsPermissionRoute>> = listOf(
        Intent(ACTION_MANAGE_PERMISSION_APPS)
            .putExtra(EXTRA_PERMISSION_NAME, Manifest.permission.READ_CONTACTS) to
            ContactsPermissionRoute.DIRECT,
        Intent(ACTION_PRIVACY_CONTROLS) to ContactsPermissionRoute.PERMISSION_MANAGER,
        Intent(Settings.ACTION_PRIVACY_SETTINGS) to ContactsPermissionRoute.PRIVACY_SETTINGS
    )

    /** Which page [open] will actually reach, so the screen can explain the remaining taps. */
    fun route(): ContactsPermissionRoute =
        pages.firstOrNull { (intent, _) -> canLaunch(intent) }?.second ?: ContactsPermissionRoute.PRIVACY_SETTINGS

    fun open() {
        val page = pages.firstOrNull { (intent, _) -> canLaunch(intent) } ?: return
        activity.startActivityIfAvailable(page.first)
    }

    /** Resolvable and not behind a permission this app lacks — the guard that keeps the exact page from third parties. */
    private fun canLaunch(intent: Intent): Boolean {
        val info = activity.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        val guard = info.permission ?: return true
        return activity.checkSelfPermission(guard) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        // Intent.ACTION_MANAGE_PERMISSION_APPS / EXTRA_PERMISSION_NAME are @SystemApi
        // constants; the activity behind them is exported by the permission controller.
        const val ACTION_MANAGE_PERMISSION_APPS = "android.intent.action.MANAGE_PERMISSION_APPS"
        const val EXTRA_PERMISSION_NAME = "android.intent.extra.PERMISSION_NAME"

        // Settings' "Privacy controls" page; exported but not a public Settings.ACTION_* constant.
        const val ACTION_PRIVACY_CONTROLS = "android.settings.PRIVACY_CONTROLS"
    }
}

/** Starts [intent] if some activity accepts it; false when none does or the OS refuses. */
fun Activity.startActivityIfAvailable(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
