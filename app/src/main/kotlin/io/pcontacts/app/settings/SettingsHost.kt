// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

// detekt RedundantSuspendModifier requires type resolution to be accurate. We
// don't run detekt with TR, so the rule misfires on every private suspend fun
// in this file — they all either call suspend Room DAO methods or are bound as
// `suspend () -> T` seams to SettingsViewModel and so cannot drop `suspend`.
@file:Suppress("RedundantSuspendModifier")

package io.pcontacts.app.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import io.pcontacts.app.account.LogoutHelper
import io.pcontacts.app.account.MissingContactsPermissionException
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.sync.SyncErrorCodes
import io.pcontacts.app.sync.SyncRunningMonitor
import io.pcontacts.app.sync.SyncScheduler
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.sync.auth.LogoutOrchestrator
import io.pcontacts.core.sync.contacts.ChangeOp
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.feature.settings.ConflictInfo
import io.pcontacts.feature.settings.ConflictResolution
import io.pcontacts.feature.settings.ContactsAccessKind
import io.pcontacts.feature.settings.LastSyncSummary
import io.pcontacts.feature.settings.OutboxStats
import io.pcontacts.feature.settings.PendingDelete
import io.pcontacts.feature.settings.QuarantinedChange
import io.pcontacts.feature.settings.QuarantinedOperation
import io.pcontacts.feature.settings.SettingsActionResult
import io.pcontacts.feature.settings.SettingsActions
import io.pcontacts.feature.settings.SettingsViewModel
import io.pcontacts.feature.settings.SyncProgress
import io.pcontacts.feature.settings.UnverifiedContactSummary
import io.pcontacts.feature.settings.VerificationStats

/**
 * Wires the Settings screen to the Android side (AccountManager,
 * ContentResolver, Room, LogoutHelper) for whichever activity hosts
 * it. Every function here is a tiny seam bound to [SettingsViewModel];
 * the host calls [onResume] / [onPause] / [dispose] with its lifecycle.
 */
// Manual-DI wiring hub: the function count is the seam surface.
@Suppress("TooManyFunctions")
class SettingsHost(
    private val activity: ComponentActivity,
    private val onSignedOut: () -> Unit
) {
    private val context get() = activity.applicationContext
    private val logoutHelper by lazy { LogoutHelper(context) }
    private val userPrefs by lazy { SharedPreferencesUserPreferences(context) }
    private val db by lazy { DatabaseFactory.create(context) }

    val viewModel: SettingsViewModel by lazy {
        SettingsViewModel(
            syncNow = ::performSyncNow,
            signOut = ::performSignOut,
            queryVerificationStats = ::queryVerificationStats,
            queryUnverifiedContacts = ::queryUnverifiedContacts,
            queryLastSync = ::queryLastSync,
            openContactInSystem = ::openContactInSystem,
            queryOutboxStats = ::queryOutboxStats,
            queryPendingDeletes = ::queryPendingDeletes,
            queryConflicts = ::queryConflicts,
            queryQuarantinedChanges = ::queryQuarantinedChanges,
            retryQuarantinedChange = { SyncBootstrap.retryQuarantinedChange(context, it) },
            discardQuarantinedChange = { SyncBootstrap.discardQuarantinedChange(context, it) },
            cancelDelete = ::cancelPendingDelete,
            resolveConflict = ::resolveConflict,
            queryContactsAccessApps = { ContactsAccessApps.list(activity, ContactsAccessKind.USER) },
            querySystemContactsAccessApps = { ContactsAccessApps.list(activity, ContactsAccessKind.SYSTEM) },
            onSyncIntervalChanged = ::handleSyncIntervalChanged,
            querySyncProgress = ::querySyncProgress,
            querySystemNoticeDismissed = { userPrefs.systemContactsNoticeDismissed },
            dismissSystemNotice = { userPrefs.systemContactsNoticeDismissed = true },
            initialSyncIntervalHours = userPrefs.syncIntervalHours
        )
    }

    private val syncRunningMonitor = SyncRunningMonitor(
        account = ::currentAccount,
        onChange = { viewModel.updateSyncRunning(it) }
    )

    fun actions() = SettingsActions(
        onSignedOut = onSignedOut,
        onOpenLinkedImport = { activity.startActivity(Intent(activity, LinkedImportActivity::class.java)) },
        onOpenContactsAccess = { kind -> activity.startActivity(ContactsAccessActivity.intent(activity, kind)) },
        onOpenContactsStorage = contactsStorageAction()
    )

    fun onResume() = syncRunningMonitor.start()

    fun onPause() = syncRunningMonitor.stop()

    fun dispose() = viewModel.dispose()

    fun currentAccount(): Account? =
        AccountManager.get(activity).getAccountsByType(PROTON_ACCOUNT_TYPE).firstOrNull()

    private suspend fun performSyncNow(): SettingsActionResult {
        val account = currentAccount()
            ?: return SettingsActionResult.Failure(reason = "no_account")
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
        return SettingsActionResult.Success()
    }

    private suspend fun queryLastSync(): LastSyncSummary {
        val status = SyncBootstrap.loadLauncherStatus(context)
        val failureMessage = if (status.lastSyncFailed) {
            activity.getString(SyncErrorCodes.messageRes(status.lastSyncErrorCode))
        } else {
            null
        }
        return LastSyncSummary(
            syncedAtMillis = status.lastSyncedAtMillis,
            failureMessage = failureMessage,
            failedContacts = status.failedContacts
        )
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
        val (total, unverified) = SyncBootstrap.countVerificationStats(context)
        return VerificationStats(totalContacts = total, unverifiedContacts = unverified)
    }

    private suspend fun queryUnverifiedContacts(): List<UnverifiedContactSummary> {
        val refs = SyncBootstrap.listUnverifiedContacts(context)
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
        return activity.contentResolver.query(
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
        val lookupUri = ContactsContract.RawContacts.getContactLookupUri(activity.contentResolver, rawUri)
            ?: return
        val view = Intent(Intent.ACTION_VIEW, lookupUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            // No Contacts app available — silently no-op rather than crash.
        }
    }

    /** The engine's "N of total" for the run in flight; null once the adapter has cleared it. */
    private fun querySyncProgress(): SyncProgress? =
        SyncProgress(userPrefs.syncProgressDone, userPrefs.syncProgressTotal).takeIf { it.total > 0 }

    private fun handleSyncIntervalChanged(hours: Long) {
        userPrefs.syncIntervalHours = hours
        SyncScheduler.reschedule(context, hours)
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

    /**
     * Names come from ContactsContract, not from our Room mapping
     * (ADR-0007), so the resolution happens here rather than in
     * `:core:sync`. A null name means the local row is gone — expected
     * for a failed deletion.
     */
    private suspend fun queryQuarantinedChanges(): List<QuarantinedChange> {
        val refs = SyncBootstrap.listQuarantinedChanges(context)
        if (refs.isEmpty()) return emptyList()
        val names = resolveDisplayNames(refs.mapNotNull { it.androidRawContactId })
        return refs.map { ref ->
            QuarantinedChange(
                outboxId = ref.outboxId,
                displayName = ref.androidRawContactId?.let { names[it] },
                operation = ref.op.toUiOperation(),
                reason = ref.lastError,
                rawContactId = ref.androidRawContactId
            )
        }
    }

    private suspend fun queryConflicts(): List<ConflictInfo> =
        db.contactMapDao().listConflicts().map { entity ->
            ConflictInfo(
                protonContactId = entity.protonContactId,
                displayName = null,
                conflictFields = entity.lastError?.removePrefix("conflict: ")
            )
        }

    private suspend fun cancelPendingDelete(protonContactId: String) {
        db.outboxDao().deleteByContact(protonContactId)
    }

    private suspend fun resolveConflict(protonContactId: String, resolution: ConflictResolution) {
        SyncBootstrap.resolveConflict(activity, protonContactId, useLocal = resolution == ConflictResolution.USE_LOCAL)
    }

    /**
     * Android 15+ "Contacts storage" (default account for new
     * contacts). Null when the screen is missing so the button hides.
     */
    private fun contactsStorageAction(): (() -> Unit)? {
        val intent = Intent(ACTION_SET_DEFAULT_ACCOUNT)
        if (activity.packageManager.resolveActivity(intent, 0) == null) return null
        return { activity.startActivityIfAvailable(intent) }
    }

    private companion object {
        // ContactsContract.Settings.ACTION_SET_DEFAULT_ACCOUNT (API 35); spelled out for minSdk 26.
        const val ACTION_SET_DEFAULT_ACCOUNT = "android.provider.action.SET_DEFAULT_ACCOUNT"
    }
}

/**
 * A null [ChangeOp] means the stored op_type is not one this build
 * understands; the UI still lists the row so it can be discarded.
 */
private fun ChangeOp?.toUiOperation(): QuarantinedOperation = when (this) {
    ChangeOp.CREATE -> QuarantinedOperation.CREATE
    ChangeOp.UPDATE -> QuarantinedOperation.UPDATE
    ChangeOp.DELETE -> QuarantinedOperation.DELETE
    null -> QuarantinedOperation.UNKNOWN
}
