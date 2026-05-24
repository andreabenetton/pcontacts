// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.pcontacts.app.MainActivity
import io.pcontacts.app.account.LogoutHelper
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.sync.SyncScheduler
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.feature.settings.ConflictInfo
import io.pcontacts.feature.settings.ConflictResolution
import io.pcontacts.feature.settings.OutboxStats
import io.pcontacts.feature.settings.PendingDelete
import io.pcontacts.feature.settings.SettingsActionResult
import io.pcontacts.feature.settings.SettingsScreen
import io.pcontacts.feature.settings.SettingsViewModel
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
    private val viewModel by lazy {
        SettingsViewModel(
            syncNow = ::performSyncNow,
            signOut = ::performSignOut,
            queryVerificationStats = ::queryVerificationStats,
            queryOutboxStats = ::queryOutboxStats,
            queryPendingDeletes = ::queryPendingDeletes,
            queryConflicts = ::queryConflicts,
            cancelDelete = ::cancelPendingDelete,
            resolveConflict = ::resolveConflict,
            onSyncIntervalChanged = ::handleSyncIntervalChanged,
            initialSyncIntervalHours = userPrefs.syncIntervalHours
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PcontactsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val account = currentAccount()
                    if (account == null) {
                        Text("No Proton account. Sign in from the launcher.")
                    } else {
                        SettingsScreen(
                            viewModel = viewModel,
                            onSignedOut = ::finishToLauncher
                        )
                    }
                }
            }
        }
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
        val result = logoutHelper.signOut(account)
        return if (result.successful) {
            SettingsActionResult.Success(message = "Signed out (${result.contactsDeleted} contacts removed).")
        } else {
            // Aggregate the non-sensitive error tags into one string for the UI.
            SettingsActionResult.Failure(reason = result.errors.joinToString(prefix = "errors: "))
        }
    }

    private suspend fun queryVerificationStats(): VerificationStats {
        val (total, unverified) = SyncBootstrap.countVerificationStats(applicationContext)
        return VerificationStats(totalContacts = total, unverifiedContacts = unverified)
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
