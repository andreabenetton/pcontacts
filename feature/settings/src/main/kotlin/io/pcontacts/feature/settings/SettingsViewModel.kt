// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plain ViewModel (not androidx.lifecycle.ViewModel) so the whole
 * class is pure-JVM testable. Mirrors the LoginViewModel pattern:
 * actions are caller-supplied function-type seams, scope is
 * injectable, workDispatcher is injectable.
 *
 * `syncNow` triggers ContentResolver.requestSync; expected to return
 * immediately and let the SyncAdapter run async — the result message
 * comes from a stats query the activity does on completion.
 *
 * `signOut` runs the full LogoutOrchestrator chain; returns true on
 * success (every step finished without error), false otherwise.
 */
@Suppress("LongParameterList") // many injectable seams by design; see class kdoc
class SettingsViewModel(
    private val syncNow: suspend () -> SettingsActionResult,
    private val signOut: suspend () -> SettingsActionResult,
    private val queryVerificationStats: suspend () -> VerificationStats? = { null },
    private val queryUnverifiedContacts: suspend () -> List<UnverifiedContactSummary> = { emptyList() },
    private val openContactInSystem: (Long) -> Unit = {},
    private val queryOutboxStats: suspend () -> OutboxStats = { OutboxStats(0, 0) },
    private val queryPendingDeletes: suspend () -> List<PendingDelete> = { emptyList() },
    private val queryConflicts: suspend () -> List<ConflictInfo> = { emptyList() },
    private val queryQuarantinedChanges: suspend () -> List<QuarantinedChange> = { emptyList() },
    private val retryQuarantinedChange: suspend (Long) -> Unit = {},
    private val discardQuarantinedChange: suspend (Long) -> Unit = {},
    private val cancelDelete: suspend (String) -> Unit = {},
    private val resolveConflict: suspend (String, ConflictResolution) -> Unit = { _, _ -> },
    private val queryContactsAccessApps: suspend () -> List<ContactsAccessApp> = { emptyList() },
    private val querySystemContactsAccessApps: suspend () -> List<ContactsAccessApp> = { emptyList() },
    private val onSyncIntervalChanged: (Long) -> Unit = {},
    initialSyncIntervalHours: Long = SyncInterval.TWELVE_HOURS.hours,
    private val scope: CoroutineScope = MainScope(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _verificationStats = MutableStateFlow<VerificationStats?>(null)
    val verificationStats: StateFlow<VerificationStats?> = _verificationStats.asStateFlow()

    private val _unverifiedContacts = MutableStateFlow<List<UnverifiedContactSummary>>(emptyList())
    val unverifiedContacts: StateFlow<List<UnverifiedContactSummary>> = _unverifiedContacts.asStateFlow()

    private val _unverifiedDialogOpen = MutableStateFlow(false)
    val unverifiedDialogOpen: StateFlow<Boolean> = _unverifiedDialogOpen.asStateFlow()

    private val _contactsAccessDialogOpen = MutableStateFlow(false)
    val contactsAccessDialogOpen: StateFlow<Boolean> = _contactsAccessDialogOpen.asStateFlow()

    private val _outboxStats = MutableStateFlow(OutboxStats(0, 0))
    val outboxStats: StateFlow<OutboxStats> = _outboxStats.asStateFlow()

    private val _pendingDeletes = MutableStateFlow<List<PendingDelete>>(emptyList())
    val pendingDeletes: StateFlow<List<PendingDelete>> = _pendingDeletes.asStateFlow()

    private val _conflicts = MutableStateFlow<List<ConflictInfo>>(emptyList())
    val conflicts: StateFlow<List<ConflictInfo>> = _conflicts.asStateFlow()

    private val _quarantinedChanges = MutableStateFlow<List<QuarantinedChange>>(emptyList())
    val quarantinedChanges: StateFlow<List<QuarantinedChange>> = _quarantinedChanges.asStateFlow()

    private val _quarantinedDialogOpen = MutableStateFlow(false)
    val quarantinedDialogOpen: StateFlow<Boolean> = _quarantinedDialogOpen.asStateFlow()

    private val _contactsAccessApps = MutableStateFlow<List<ContactsAccessApp>>(emptyList())
    val contactsAccessApps: StateFlow<List<ContactsAccessApp>> = _contactsAccessApps.asStateFlow()

    private val _systemContactsAccessApps = MutableStateFlow<List<ContactsAccessApp>>(emptyList())
    val systemContactsAccessApps: StateFlow<List<ContactsAccessApp>> = _systemContactsAccessApps.asStateFlow()

    private val _systemContactsAccessDialogOpen = MutableStateFlow(false)
    val systemContactsAccessDialogOpen: StateFlow<Boolean> = _systemContactsAccessDialogOpen.asStateFlow()

    private val _syncInterval = MutableStateFlow(SyncInterval.fromHours(initialSyncIntervalHours))
    val syncInterval: StateFlow<SyncInterval> = _syncInterval.asStateFlow()

    private var pendingJob: Job? = null

    init {
        scope.launch { refreshSyncStatus() }
    }

    fun setSyncInterval(interval: SyncInterval) {
        _syncInterval.value = interval
        onSyncIntervalChanged(interval.hours)
    }

    private suspend fun refreshSyncStatus() {
        withContext(workDispatcher) {
            _verificationStats.value = try { queryVerificationStats() } catch (_: Exception) { null }
            _unverifiedContacts.value = try { queryUnverifiedContacts() } catch (_: Exception) { emptyList() }
            _outboxStats.value = try { queryOutboxStats() } catch (_: Exception) { OutboxStats(0, 0) }
            _pendingDeletes.value = try { queryPendingDeletes() } catch (_: Exception) { emptyList() }
            _conflicts.value = try { queryConflicts() } catch (_: Exception) { emptyList() }
            _quarantinedChanges.value =
                try { queryQuarantinedChanges() } catch (_: Exception) { emptyList() }
            _contactsAccessApps.value = try { queryContactsAccessApps() } catch (_: Exception) { emptyList() }
            _systemContactsAccessApps.value =
                try { querySystemContactsAccessApps() } catch (_: Exception) { emptyList() }
        }
    }

    fun showUnverifiedContactsDialog() {
        _unverifiedDialogOpen.value = true
    }

    fun dismissUnverifiedContactsDialog() {
        _unverifiedDialogOpen.value = false
    }

    fun openUnverifiedContactInSystem(rawContactId: Long) {
        openContactInSystem(rawContactId)
    }

    fun showQuarantinedChangesDialog() {
        _quarantinedDialogOpen.value = true
    }

    fun dismissQuarantinedChangesDialog() {
        _quarantinedDialogOpen.value = false
    }

    /**
     * Puts one failed change back in the queue and refreshes the
     * counts. The dialog closes itself once the last row is gone —
     * an empty failure list has nothing left to show.
     */
    fun retryQuarantined(outboxId: Long) {
        scope.launch {
            withContext(workDispatcher) { retryQuarantinedChange(outboxId) }
            refreshSyncStatus()
            closeQuarantinedDialogIfEmpty()
        }
    }

    fun discardQuarantined(outboxId: Long) {
        scope.launch {
            withContext(workDispatcher) { discardQuarantinedChange(outboxId) }
            refreshSyncStatus()
            closeQuarantinedDialogIfEmpty()
        }
    }

    private fun closeQuarantinedDialogIfEmpty() {
        if (_quarantinedChanges.value.isEmpty()) {
            _quarantinedDialogOpen.value = false
        }
    }

    fun showContactsAccessDialog() {
        _contactsAccessDialogOpen.value = true
    }

    fun dismissContactsAccessDialog() {
        _contactsAccessDialogOpen.value = false
    }

    fun showSystemContactsAccessDialog() {
        _systemContactsAccessDialogOpen.value = true
    }

    fun dismissSystemContactsAccessDialog() {
        _systemContactsAccessDialogOpen.value = false
    }

    fun triggerSyncNow() {
        if (_uiState.value is SettingsUiState.Syncing) return
        _uiState.value = SettingsUiState.Syncing
        pendingJob = scope.launch {
            val result = withContext(workDispatcher) { syncNow() }
            _uiState.value = when (result) {
                is SettingsActionResult.Success ->
                    SettingsUiState.SyncDone(result.message ?: "Sync requested")
                is SettingsActionResult.Failure ->
                    SettingsUiState.SyncFailed(result.reason)
            }
            refreshSyncStatus()
        }
    }

    fun triggerSignOut() {
        if (_uiState.value is SettingsUiState.SigningOut) return
        _uiState.value = SettingsUiState.SigningOut
        pendingJob = scope.launch {
            val result = withContext(workDispatcher) { signOut() }
            _uiState.value = when (result) {
                is SettingsActionResult.Success -> SettingsUiState.SignedOut
                is SettingsActionResult.Failure -> SettingsUiState.SignOutFailed(result.reason)
            }
        }
    }

    fun cancelPendingDelete(protonContactId: String) {
        scope.launch {
            withContext(workDispatcher) { cancelDelete(protonContactId) }
            refreshSyncStatus()
        }
    }

    fun resolveContactConflict(protonContactId: String, resolution: ConflictResolution) {
        scope.launch {
            withContext(workDispatcher) { resolveConflict(protonContactId, resolution) }
            refreshSyncStatus()
        }
    }

    fun reset() {
        pendingJob?.cancel()
        pendingJob = null
        _uiState.value = SettingsUiState.Idle
    }

    fun dispose() {
        pendingJob?.cancel()
        scope.cancel()
    }
}

sealed interface SettingsActionResult {
    data class Success(val message: String? = null) : SettingsActionResult
    data class Failure(val reason: String) : SettingsActionResult
}

data class VerificationStats(
    val totalContacts: Int,
    val unverifiedContacts: Int
)

/**
 * One row for the unverified-contacts dialog. `displayName` is
 * resolved upstream (in `:app`) via ContentResolver against
 * ContactsContract; null means the contact has no name in the
 * system DB (either the Proton contact had no FN/N and lost the
 * Issue 1 aggregation race, or the row was deleted under us).
 *
 * `lastError` is the persisted reason stored on
 * `ContactMapEntity.lastError` — typically the exception's class
 * name plus a short hint, never sensitive content (per
 * `:core:logging` redactor).
 */
data class UnverifiedContactSummary(
    val rawContactId: Long,
    val protonContactId: String,
    val displayName: String?,
    val lastError: String?
)
