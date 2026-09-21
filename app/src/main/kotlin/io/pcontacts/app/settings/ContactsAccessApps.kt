// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.pcontacts.feature.settings.ContactsAccessApp
import io.pcontacts.feature.settings.ContactsAccessKind

/** The READ_CONTACTS transparency lists, split into user-installed and OS-bundled apps. */
object ContactsAccessApps {

    fun list(context: Context, kind: ContactsAccessKind): List<ContactsAccessApp> = when (kind) {
        ContactsAccessKind.USER -> userApps(context)
        ContactsAccessKind.SYSTEM -> systemApps(context)
    }

    private fun userApps(context: Context): List<ContactsAccessApp> =
        holders(context) { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg.packageName) != null && !isSystemApp(pkg.applicationInfo)
        }

    /**
     * OS-bundled packages that hold READ_CONTACTS. We don't gate on a
     * launcher intent here — most preinstalled snoopers (Google Play
     * Services, sync providers, OEM background services) have none and
     * are exactly what the user can't remove on stock Android.
     */
    private fun systemApps(context: Context): List<ContactsAccessApp> =
        holders(context) { pkg -> pkg.packageName != "android" && isSystemApp(pkg.applicationInfo) }

    private fun holders(context: Context, accept: (PackageInfo) -> Boolean): List<ContactsAccessApp> {
        val pm = context.packageManager
        return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .filter { pkg ->
                pkg.packageName != context.packageName &&
                    pkg.requestedPermissions?.contains(Manifest.permission.READ_CONTACTS) == true &&
                    pm.checkPermission(Manifest.permission.READ_CONTACTS, pkg.packageName) == PackageManager.PERMISSION_GRANTED &&
                    accept(pkg)
            }
            .map { pkg ->
                ContactsAccessApp(
                    appName = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName,
                    packageName = pkg.packageName
                )
            }
            .sortedBy { it.appName }
    }

    private fun isSystemApp(info: ApplicationInfo?): Boolean {
        if (info == null) return false
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return info.flags and systemFlags != 0
    }
}
