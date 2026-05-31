// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pcontacts.app.MainActivity
import io.pcontacts.app.account.LogoutHelper
import io.pcontacts.app.account.MissingContactsPermissionException
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.permissions.ContactsPermissionBanner
import io.pcontacts.app.permissions.ContactsPermissionState
import io.pcontacts.app.permissions.ContactsPermissionStatus
import io.pcontacts.app.sync.SyncScheduler
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.core.sync.auth.LogoutOrchestrator
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.feature.settings.ContactsAccessApp
import io.pcontacts.feature.settings.ConflictInfo
import io.pcontacts.feature.settings.ConflictResolution
import io.pcontacts.feature.settings.OutboxStats
import io.pcontacts.feature.settings.PendingDelete
import io.pcontacts.feature.settings.SettingsActionResult
import io.pcontacts.feature.settings.SettingsScreen
import io.pcontacts.feature.settings.SettingsViewModel
import io.pcontacts.feature.settings.UnverifiedContactSummary
import io.pcontacts.feature.settings.VerificationStats

/**
 * Hosts the Settings Compose surface with Sync Now + Sign Out
 * actions wired to the actual Android side (AccountManager,
 * ContentResolver, LogoutHelper).
 *
 * If no Proton account is registered, every action surfaces as a
 * 'no_account' failure rather than crashing — the user lands here
 * before logging in via deep link / shortcut.
 */
class SettingsActivity : ComponentActivity() {

    private val logoutHelper by lazy { LogoutHelper(applicationContext) }
    private val userPrefs by lazy { SharedPreferencesUserPreferences(applicationContext) }
    private val db by lazy { DatabaseFactory.create(applicationContext) }
    private var contactsPermissionStatus by mutableStateOf(ContactsPermissionStatus.GRANTED)

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        userPrefs.contactsPermissionRequested = true
        contactsPermissionStatus = ContactsPermissionState.check(this, true)
    }
    private val viewModel by lazy {
        SettingsViewModel(
            syncNow = ::performSyncNow,
            signOut = ::performSignOut,
            queryVerificationStats = ::queryVerificationStats,
            queryUnverifiedContacts = ::queryUnverifiedContacts,
            openContactInSystem = ::openContactInSystem,
            queryOutboxStats = ::queryOutboxStats,
            queryPendingDeletes = ::queryPendingDeletes,
            queryConflicts = ::queryConflicts,
            cancelDelete = ::cancelPendingDelete,
            resolveConflict = ::resolveConflict,
            queryContactsAccessApps = ::queryContactsAccessApps,
            querySystemContactsAccessApps = ::querySystemContactsAccessApps,
            onSyncIntervalChanged = ::handleSyncIntervalChanged,
            initialSyncIntervalHours = userPrefs.syncIntervalHours
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactsPermissionStatus = ContactsPermissionState.check(
            this, userPrefs.contactsPermissionRequested
        )
        setContent {
            PcontactsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val account = currentAccount()
                    if (account == null) {
                        Text("No Proton account. Sign in from the launcher.")
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (contactsPermissionStatus != ContactsPermissionStatus.GRANTED) {
                                ContactsPermissionBanner(
                                    isPermanentlyDenied = contactsPermissionStatus == ContactsPermissionStatus.PERMANENTLY_DENIED,
                                    onAction = ::handleContactsPermissionAction,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                            SettingsScreen(
                                viewModel = viewModel,
                                onSignedOut = ::finishToLauncher,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        contactsPermissionStatus = ContactsPermissionState.check(
            this, userPrefs.contactsPermissionRequested
        )
    }

    override fun onDestroy() {
        viewModel.dispose()
        super.onDestroy()
    }

    private suspend fun performSyncNow(): SettingsActionResult {
        val account = currentAccount()
            ?: return SettingsActionResult.Failure(reason = "no_account")
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
        return SettingsActionResult.Success(message = "Sync requested. Check the system Contacts app shortly.")
    }

    private suspend fun performSignOut(): SettingsActionResult {
        val account = currentAccount()
            ?: return SettingsActionResult.Failure(reason = "no_account")
        return try {
            val result = logoutHelper.signOut(account)
            val criticalErrors = result.errors.filter { it != LogoutOrchestrator.LOGOUT_ERR_REVOKE }
            if (criticalErrors.isEmpty()) {
                SettingsActionResult.Success(message = "Signed out (${result.contactsDeleted} contacts removed).")
            } else {
                SettingsActionResult.Failure(reason = criticalErrors.joinToString())
            }
        } catch (_: MissingContactsPermissionException) {
            SettingsActionResult.Failure(reason = "missing_contacts_permission")
        }
    }

    private suspend fun queryVerificationStats(): VerificationStats {
        val (total, unverified) = SyncBootstrap.countVerificationStats(applicationContext)
        return VerificationStats(totalContacts = total, unverifiedContacts = unverified)
    }

    private suspend fun queryUnverifiedContacts(): List<UnverifiedContactSummary> {
        val refs = SyncBootstrap.listUnverifiedContacts(applicationContext)
        if (refs.isEmpty()) return emptyList()
        val names = resolveDisplayNames(refs.map { it.androidRawContactId })
        return refs.map { ref ->
            UnverifiedContactSummary(
                rawContactId = ref.androidRawContactId,
                protonContactId = ref.protonContactId,
                displayName = names[ref.androidRawContactId],
                lastError = ref.lastError
            )
        }
    }

    /**
     * Resolves the aggregated display name (the name Contacts apps
     * show — may come from a merged WhatsApp / Telegram row, not
     * just our Proton row) for each of the supplied
     * RawContacts._ID values. Returns a map keyed by rawContactId.
     */
    private fun resolveDisplayNames(rawIds: List<Long>): Map<Long, String?> {
        if (rawIds.isEmpty()) return emptyMap()
        val placeholders = rawIds.joinToString(",") { "?" }
        val selection = "${ContactsContract.RawContacts._ID} IN ($placeholders)"
        val args = rawIds.map { it.toString() }.toTypedArray()
        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY
        )
        return contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            val out = HashMap<Long, String?>(rawIds.size)
            while (cursor.moveToNext()) {
                out[cursor.getLong(0)] = cursor.getString(1)
            }
            out
        } ?: emptyMap()
    }

    /**
     * Opens the aggregated Contact (not just our RawContact) in
     * whichever Contacts app handles ACTION_VIEW. The user sees the
     * full merged view — Proton + WhatsApp + Telegram rows together
     * — which matches how they'd inspect the contact normally.
     */
    private fun openContactInSystem(rawContactId: Long) {
        val rawUri = Uri.withAppendedPath(
            ContactsContract.RawContacts.CONTENT_URI,
            rawContactId.toString()
        )
        val lookupUri = ContactsContract.RawContacts.getContactLookupUri(contentResolver, rawUri)
            ?: return
        val view = Intent(Intent.ACTION_VIEW, lookupUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(view)
        } catch (_: android.content.ActivityNotFoundException) {
            // No Contacts app available — silently no-op rather than crash.
        }
    }

    private fun handleSyncIntervalChanged(hours: Long) {
        userPrefs.syncIntervalHours = hours
        SyncScheduler.reschedule(applicationContext, hours)
    }

    private suspend fun queryOutboxStats(): OutboxStats {
        val outboxDao = db.outboxDao()
        return OutboxStats(
            pending = outboxDao.countPending(),
            quarantined = outboxDao.countQuarantined()
        )
    }

    private suspend fun queryPendingDeletes(): List<PendingDelete> =
        db.outboxDao().listPendingDeletes().map { entry ->
            PendingDelete(
                protonContactId = entry.protonContactId,
                createdAt = entry.createdAt
            )
        }

    private suspend fun queryConflicts(): List<ConflictInfo> =
        db.contactMapDao().listConflicts().map { entity ->
            ConflictInfo(
                protonContactId = entity.protonContactId,
                displayName = null,
                conflictFields = entity.lastError?.removePrefix("conflict: ")
            )
        }

    private fun queryContactsAccessApps(): List<ContactsAccessApp> {
        val pm = packageManager
        val installed = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
        return installed
            .filter { pkg ->
                pkg.packageName != packageName &&
                    pkg.requestedPermissions?.contains(android.Manifest.permission.READ_CONTACTS) == true &&
                    pm.checkPermission(
                        android.Manifest.permission.READ_CONTACTS,
                        pkg.packageName
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    pm.getLaunchIntentForPackage(pkg.packageName) != null &&
                    !isSystemApp(pkg.applicationInfo)
            }
            .map { pkg ->
                ContactsAccessApp(
                    appName = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName,
                    packageName = pkg.packageName
                )
            }
            .sortedBy { it.appName }
    }

    /**
     * OS-bundled packages that hold READ_CONTACTS. We don't gate on a
     * launcher intent here — most preinstalled snoopers (Google Play
     * Services, sync providers, OEM background services) have none and
     * are exactly what the user can't remove on stock Android.
     */
    private fun querySystemContactsAccessApps(): List<ContactsAccessApp> {
        val pm = packageManager
        val installed = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
        return installed
            .filter { pkg ->
                pkg.packageName != packageName &&
                    pkg.packageName != "android" &&
                    pkg.requestedPermissions?.contains(android.Manifest.permission.READ_CONTACTS) == true &&
                    pm.checkPermission(
                        android.Manifest.permission.READ_CONTACTS,
                        pkg.packageName
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    isSystemApp(pkg.applicationInfo)
            }
            .map { pkg ->
                ContactsAccessApp(
                    appName = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName,
                    packageName = pkg.packageName
                )
            }
            .sortedBy { it.appName }
    }

    private fun isSystemApp(info: android.content.pm.ApplicationInfo?): Boolean {
        if (info == null) return false
        val systemFlags = android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return info.flags and systemFlags != 0
    }

    private suspend fun cancelPendingDelete(protonContactId: String) {
        db.outboxDao().deleteByContact(protonContactId)
    }

    private suspend fun resolveConflict(protonContactId: String, resolution: ConflictResolution) {
        val contactMapDao = db.contactMapDao()
        val outboxDao = db.outboxDao()
        contactMapDao.resolveConflict(protonContactId)
        when (resolution) {
            ConflictResolution.USE_LOCAL -> {
                outboxDao.insert(
                    OutboxEntity(
                        protonContactId = protonContactId,
                        opType = OutboxEntity.OpType.UPDATE,
                        payloadHash = "",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            ConflictResolution.USE_SERVER -> {
                // No outbox entry needed — next pull overwrites the local copy.
            }
        }
    }

    private fun handleContactsPermissionAction() {
        if (contactsPermissionStatus == ContactsPermissionStatus.PERMANENTLY_DENIED) {
            openAppSettings()
        } else {
            contactsPermissionLauncher.launch(ContactsPermissionState.requiredPermissions())
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun currentAccount(): Account? =
        AccountManager.get(this).getAccountsByType(PROTON_ACCOUNT_TYPE).firstOrNull()

    private fun finishToLauncher() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }
}
