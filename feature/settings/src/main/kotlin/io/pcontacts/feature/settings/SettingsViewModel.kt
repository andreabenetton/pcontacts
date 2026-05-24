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
class SettingsViewModel(
    private val syncNow: suspend () -> SettingsActionResult,
    private val signOut: suspend () -> SettingsActionResult,
    private val queryVerificationStats: suspend () -> VerificationStats? = { null },
    private val queryOutboxStats: suspend () -> OutboxStats = { OutboxStats(0, 0) },
    private val queryPendingDeletes: suspend () -> List<PendingDelete> = { emptyList() },
    private val queryConflicts: suspend () -> List<ConflictInfo> = { emptyList() },
    private val cancelDelete: suspend (String) -> Unit = {},
    private val resolveConflict: suspend (String, ConflictResolution) -> Unit = { _, _ -> },
    private val onSyncIntervalChanged: (Long) -> Unit = {},
    initialSyncIntervalHours: Long = SyncInterval.TWELVE_HOURS.hours,
    private val scope: CoroutineScope = MainScope(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _verificationStats = MutableStateFlow<VerificationStats?>(null)
    val verificationStats: StateFlow<VerificationStats?> = _verificationStats.asStateFlow()

    private val _outboxStats = MutableStateFlow(OutboxStats(0, 0))
    val outboxStats: StateFlow<OutboxStats> = _outboxStats.asStateFlow()

    private val _pendingDeletes = MutableStateFlow<List<PendingDelete>>(emptyList())
    val pendingDeletes: StateFlow<List<PendingDelete>> = _pendingDeletes.asStateFlow()

    private val _conflicts = MutableStateFlow<List<ConflictInfo>>(emptyList())
    val conflicts: StateFlow<List<ConflictInfo>> = _conflicts.asStateFlow()

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
            _outboxStats.value = try { queryOutboxStats() } catch (_: Exception) { OutboxStats(0, 0) }
            _pendingDeletes.value = try { queryPendingDeletes() } catch (_: Exception) { emptyList() }
            _conflicts.value = try { queryConflicts() } catch (_: Exception) { emptyList() }
        }
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
