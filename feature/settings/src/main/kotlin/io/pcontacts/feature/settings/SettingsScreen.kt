// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Settings, in four sections: Sync (status card, Sync now, interval),
 * Contacts (storage, linked import), Privacy (READ_CONTACTS lists) and
 * Account (Sign out). [banner] is the host's slot above the sections,
 * used for the missing-permission notice.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncInterval by viewModel.syncInterval.collectAsStateWithLifecycle()
    val syncRunning by viewModel.syncRunning.collectAsStateWithLifecycle()
    val actionInFlight = state is SettingsUiState.Syncing || state is SettingsUiState.SigningOut
    val busy = actionInFlight || syncRunning

    Scaffold(
        modifier = modifier,
        topBar = { AppTopBar() },
        snackbarHost = snackbarHost
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            banner()

            SectionHeader(R.string.settings_section_sync)
            SyncStatusCard(
                viewModel = viewModel,
                state = state,
                syncRunning = syncRunning,
                syncEnabled = !busy,
                onSignedOut = actions.onSignedOut
            )
            Spacer(Modifier.height(12.dp))
            SyncIntervalSelector(
                selected = syncInterval,
                onSelected = viewModel::setSyncInterval,
                enabled = !busy
            )

            SectionHeader(R.string.settings_section_contacts)
            ContactsStorageButton(actions.onOpenContactsStorage)
            Spacer(Modifier.height(12.dp))
            LinkedImportSection(enabled = !busy, onOpen = actions.onOpenLinkedImport)

            ContactsAccessSection(viewModel, actions)

            SectionHeader(R.string.settings_section_account)
            OutlinedButton(
                enabled = !busy,
                onClick = viewModel::triggerSignOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_sign_out))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Android 15+ opens the system "Contacts storage" screen; on older
 * versions there is no such screen, so the button stays visible but
 * disabled and says where the choice lives instead.
 */
@Composable
private fun ContactsStorageButton(open: (() -> Unit)?) {
    ActionButton(enabled = open != null, onClick = { open?.invoke() }, textRes = R.string.settings_contacts_storage)
    if (open == null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_contacts_storage_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SectionHeader(titleRes: Int) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}

// ---- Sync status card ----

private class Headline(val text: String, val tone: SyncTone)

/** Everything the headline is decided from; `now` ticks so relative times and overdue stay live. */
private class SyncFacts(
    val state: SettingsUiState,
    val syncRunning: Boolean,
    val lastSync: LastSyncSummary?,
    val outbox: OutboxStats,
    val intervalHours: Long,
    val progress: SyncProgress?,
    val now: Long
)

/**
 * One line that says what the sync is doing right now. Sign-out states
 * come first; everything else is [syncHealth] rendered.
 */
@Composable
private fun headline(facts: SyncFacts): Headline {
    val state = facts.state
    val ok = SyncTone.OK
    val warn = SyncTone.WARN
    val running = SyncTone.RUNNING
    val info = SyncTone.INFO
    when (state) {
        SettingsUiState.SigningOut -> return Headline(stringResource(R.string.settings_signing_out), running)
        SettingsUiState.SignedOut -> return Headline(stringResource(R.string.settings_signed_out), ok)
        is SettingsUiState.SignOutFailed ->
            return Headline(stringResource(R.string.settings_sign_out_failed, state.reason), warn)
        else -> Unit
    }
    val health = syncHealth(
        running = state is SettingsUiState.Syncing || facts.syncRunning,
        failed = state is SettingsUiState.SyncFailed,
        lastSync = facts.lastSync,
        outbox = facts.outbox,
        intervalHours = facts.intervalHours,
        nowMillis = facts.now
    )
    val outbox = facts.outbox
    val lastSync = facts.lastSync
    val progress = facts.progress
    return when (health) {
        SyncHealth.RUNNING -> Headline(
            text = if (progress != null && progress.total > 0) {
                stringResource(R.string.sync_state_progress, progress.done, progress.total)
            } else {
                stringResource(R.string.settings_sync_running)
            },
            tone = running
        )
        SyncHealth.FAILED -> Headline(failureText(state, lastSync), warn)
        // The counts live on the tappable status rows below; the headline only names the state.
        SyncHealth.ATTENTION -> Headline(stringResource(R.string.sync_state_attention), warn)
        SyncHealth.PENDING -> Headline(stringResource(R.string.sync_state_pending), info)
        SyncHealth.NEVER -> Headline(stringResource(R.string.sync_state_never), info)
        SyncHealth.OVERDUE -> Headline(stringResource(R.string.sync_state_overdue), warn)
        SyncHealth.UP_TO_DATE -> Headline(stringResource(R.string.sync_state_ok), ok)
    }
}

@Composable
private fun failureText(state: SettingsUiState, lastSync: LastSyncSummary?): String =
    if (state is SettingsUiState.SyncFailed) {
        stringResource(R.string.settings_sync_failed, state.reason)
    } else {
        lastSync?.failureMessage ?: stringResource(R.string.settings_sync_failed, "")
    }

/**
 * Everything about the sync in one place: the current state with the
 * Sync now action beside it, when it last ran, and the things that need
 * attention (unverified contacts, pending or failed changes, scheduled
 * deletions, conflicts) as rows.
 */
@Composable
private fun SyncStatusCard(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    syncRunning: Boolean,
    syncEnabled: Boolean,
    onSignedOut: () -> Unit
) {
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val outbox by viewModel.outboxStats.collectAsStateWithLifecycle()
    val interval by viewModel.syncInterval.collectAsStateWithLifecycle()
    val progress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val stats by viewModel.verificationStats.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    val headline = headline(SyncFacts(state, syncRunning, lastSync, outbox, interval.hours, progress, now))
    if (state is SettingsUiState.SignedOut) LaunchedEffect(Unit) { onSignedOut() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    SyncIndicator(
                        tone = headline.tone,
                        text = headline.text,
                        style = MaterialTheme.typography.titleMedium,
                        glyphSize = 24.dp
                    )
                    lastSync?.let { LastSyncLine(it, now, stats?.totalContacts) }
                }
                Spacer(Modifier.width(12.dp))
                Button(enabled = syncEnabled, onClick = viewModel::triggerSyncNow) {
                    Text(stringResource(R.string.settings_sync_now))
                }
            }
            if (headline.tone == SyncTone.RUNNING) {
                Spacer(Modifier.height(12.dp))
                val p = progress
                if (p != null && p.total > 0) {
                    LinearProgressIndicator(
                        progress = { p.done.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        StatusRows(viewModel)
    }
}

/**
 * How many contacts are synced and when the last completed run
 * happened — relative, ticking with [now]; tapping toggles the absolute
 * date and time — plus how many contacts it could not sync.
 */
@Composable
private fun LastSyncLine(info: LastSyncSummary, now: Long, contacts: Int?) {
    val syncedAt = info.lastRunAtMillis ?: info.syncedAtMillis
    var absolute by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val syncText = when {
        syncedAt == null -> stringResource(R.string.settings_last_sync_never)
        absolute -> stringResource(
            R.string.settings_last_sync,
            DateUtils.formatDateTime(context, syncedAt, ABSOLUTE_FLAGS)
        )
        // [now] ticks every 30 s, so a sync that just finished can be ahead of it; never say "In 0 minutes".
        else -> stringResource(
            R.string.settings_last_sync,
            DateUtils.getRelativeTimeSpanString(syncedAt, maxOf(now, syncedAt), DateUtils.MINUTE_IN_MILLIS)
        )
    }
    val text = if (contacts != null) {
        pluralStringResource(R.plurals.sync_contacts_count, contacts, contacts) + " · " + syncText
    } else {
        syncText
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { absolute = !absolute }
    )
    if (info.failedContacts > 0) {
        val failed = info.failedContacts
        Text(
            text = pluralStringResource(R.plurals.settings_sync_failed_contacts, failed, failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** The attention rows of the status card and the dialogs they open. */
@Composable
private fun StatusRows(viewModel: SettingsViewModel) {
    val verificationStats by viewModel.verificationStats.collectAsStateWithLifecycle()
    val unverifiedContacts by viewModel.unverifiedContacts.collectAsStateWithLifecycle()
    val unverifiedDialogOpen by viewModel.unverifiedDialogOpen.collectAsStateWithLifecycle()
    val outboxStats by viewModel.outboxStats.collectAsStateWithLifecycle()
    val pendingDeletes by viewModel.pendingDeletes.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val quarantinedChanges by viewModel.quarantinedChanges.collectAsStateWithLifecycle()
    val quarantinedDialogOpen by viewModel.quarantinedDialogOpen.collectAsStateWithLifecycle()

    verificationStats?.takeIf { it.unverifiedContacts > 0 }?.let { stats ->
        StatusRow(
            text = stringResource(R.string.verification_warning, stats.unverifiedContacts, stats.totalContacts),
            detail = stringResource(R.string.verification_tap_to_review),
            error = true,
            onClick = viewModel::showUnverifiedContactsDialog
        )
    }
    if (outboxStats.pending > 0) {
        StatusRow(
            text = pluralStringResource(R.plurals.outbox_pending, outboxStats.pending, outboxStats.pending),
            detail = null,
            error = false,
            onClick = null
        )
    }
    if (outboxStats.quarantined > 0) {
        StatusRow(
            text = pluralStringResource(R.plurals.outbox_quarantined, outboxStats.quarantined, outboxStats.quarantined),
            detail = stringResource(R.string.quarantined_tap_to_review),
            error = true,
            onClick = viewModel::showQuarantinedChangesDialog
        )
    }
    if (pendingDeletes.isNotEmpty()) PendingDeleteRows(pendingDeletes, viewModel::cancelPendingDelete)
    if (conflicts.isNotEmpty()) ConflictRows(conflicts, viewModel::resolveContactConflict)

    if (unverifiedDialogOpen) {
        UnverifiedContactsDialog(
            contacts = unverifiedContacts,
            onOpenContact = viewModel::openUnverifiedContactInSystem,
            onDismiss = viewModel::dismissUnverifiedContactsDialog
        )
    }
    if (quarantinedDialogOpen) {
        QuarantinedChangesDialog(
            changes = quarantinedChanges,
            onOpenContact = viewModel::openQuarantinedContactInSystem,
            onRetry = viewModel::retryQuarantined,
            onDiscard = viewModel::discardQuarantined,
            onDismiss = viewModel::dismissQuarantinedChangesDialog
        )
    }
}

@Composable
private fun StatusRow(text: String, detail: String?, error: Boolean, onClick: (() -> Unit)?) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PendingDeleteRows(deletes: List<PendingDelete>, onCancel: (String) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = pluralStringResource(R.plurals.pending_delete_count, deletes.size, deletes.size),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.pending_delete_grace),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        deletes.forEach { del ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = del.protonContactId.take(12) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onCancel(del.protonContactId) }) {
                    Text(stringResource(R.string.pending_delete_cancel))
                }
            }
        }
    }
}

@Composable
private fun ConflictRows(conflicts: List<ConflictInfo>, onResolve: (String, ConflictResolution) -> Unit) {
    var selectedConflict by remember { mutableStateOf<ConflictInfo?>(null) }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = pluralStringResource(R.plurals.conflict_count, conflicts.size, conflicts.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = stringResource(R.string.conflict_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        conflicts.forEach { conflict ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conflict.displayName ?: conflict.protonContactId.take(12),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { selectedConflict = conflict }) {
                    Text(stringResource(R.string.conflict_resolve))
                }
            }
        }
    }

    selectedConflict?.let { conflict ->
        ConflictResolutionDialog(
            conflict = conflict,
            onResolve = { resolution ->
                onResolve(conflict.protonContactId, resolution)
                selectedConflict = null
            },
            onDismiss = { selectedConflict = null }
        )
    }
}

// ---- Privacy section ----

/** The two READ_CONTACTS transparency banners; each opens [ContactsAccessScreen] via the host. */
@Composable
private fun ContactsAccessSection(viewModel: SettingsViewModel, actions: SettingsActions) {
    val contactsAccessApps by viewModel.contactsAccessApps.collectAsStateWithLifecycle()
    val systemContactsAccessApps by viewModel.systemContactsAccessApps.collectAsStateWithLifecycle()
    val systemNoticeDismissed by viewModel.systemNoticeDismissed.collectAsStateWithLifecycle()
    if (contactsAccessApps.isEmpty() && systemContactsAccessApps.isEmpty()) return

    SectionHeader(R.string.settings_section_privacy)
    if (contactsAccessApps.isNotEmpty()) {
        ContactsAccessBanner(
            apps = contactsAccessApps,
            onClick = { actions.onOpenContactsAccess(ContactsAccessKind.USER) }
        )
    }
    if (systemContactsAccessApps.isNotEmpty()) {
        if (contactsAccessApps.isNotEmpty()) Spacer(Modifier.height(12.dp))
        SystemContactsAccessBanner(
            apps = systemContactsAccessApps,
            dismissed = systemNoticeDismissed,
            onView = { actions.onOpenContactsAccess(ContactsAccessKind.SYSTEM) },
            onGotIt = viewModel::acknowledgeSystemNotice,
            onOpenDeGoogledRoms = actions.onOpenDeGoogledRoms
        )
    }
}

@Composable
private fun ContactsAccessBanner(apps: List<ContactsAccessApp>, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.contacts_access_count, apps.size, apps.size),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.contacts_access_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.contacts_access_tap_to_review),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The OS-apps notice: nothing here is actionable (they cannot be
 * uninstalled), so it is information, shown once with the explanation
 * and "Got it"; afterwards a one-line link keeps the list reachable.
 */
@Composable
private fun SystemContactsAccessBanner(
    apps: List<ContactsAccessApp>,
    dismissed: Boolean,
    onView: () -> Unit,
    onGotIt: () -> Unit,
    onOpenDeGoogledRoms: () -> Unit
) {
    val count = pluralStringResource(R.plurals.system_contacts_access_count, apps.size, apps.size)
    if (dismissed) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onView).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.contacts_access_view_list),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp)
    ) {
        Text(text = count, style = MaterialTheme.typography.bodyMedium)
        LinkedText(
            templateRes = R.string.system_contacts_access_detail,
            phraseRes = R.string.de_googled_rom_link,
            onClick = onOpenDeGoogledRoms,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onView) { Text(stringResource(R.string.contacts_access_view_list)) }
            TextButton(onClick = onGotIt) { Text(stringResource(R.string.contacts_access_got_it)) }
        }
    }
}

// ---- Sync interval ----

/** A stepped slider over the fixed cadences: the platform control for picking one of a few integer values. */
@Composable
private fun SyncIntervalSelector(
    selected: SyncInterval,
    onSelected: (SyncInterval) -> Unit,
    enabled: Boolean
) {
    val options = SyncInterval.entries
    val hours = selected.hours.toInt()
    val label = stringResource(R.string.settings_sync_interval)
    val every = pluralStringResource(R.plurals.sync_interval_every, hours, hours)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = every, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = options.indexOf(selected).toFloat(),
            onValueChange = { onSelected(options[it.roundToInt()]) },
            valueRange = 0f..(options.size - 1).toFloat(),
            steps = options.size - 2,
            enabled = enabled,
            // Screen readers announce the cadence ("Every 6 hours") instead of a percentage.
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = every
            }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            options.forEach { option ->
                Text(
                    text = stringResource(R.string.sync_interval_hours_short, option.hours),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- Dialogs ----

@Composable
private fun UnverifiedContactsDialog(
    contacts: List<UnverifiedContactSummary>,
    onOpenContact: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unverified_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.verification_detail),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                if (contacts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.unverified_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    contacts.forEach { c ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenContact(c.rawContactId) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = c.displayName ?: stringResource(R.string.unverified_no_name),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            c.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.unverified_dialog_close))
            }
        }
    )
}

/**
 * Lists the changes that failed permanently, so "1 change failed" can
 * be traced to a specific contact and reason. Each row offers Retry
 * (back into the queue) and Discard (abandon the push); without them a
 * quarantined entry would sit in the outbox forever.
 */
@Composable
private fun QuarantinedChangesDialog(
    changes: List<QuarantinedChange>,
    onOpenContact: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDiscard: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quarantined_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.quarantined_detail),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                if (changes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.quarantined_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    changes.forEach { change ->
                        QuarantinedChangeRow(
                            change = change,
                            onOpenContact = onOpenContact,
                            onRetry = onRetry,
                            onDiscard = onDiscard
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.quarantined_dialog_close))
            }
        }
    )
}

@Composable
private fun QuarantinedChangeRow(
    change: QuarantinedChange,
    onOpenContact: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDiscard: (Long) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        // The details open the contact in the system app, as unverified rows do, when it still exists.
        val open = change.rawContactId?.let { id -> Modifier.clickable { onOpenContact(id) } } ?: Modifier
        Column(modifier = Modifier.fillMaxWidth().then(open)) {
            Text(
                text = change.displayName ?: stringResource(R.string.quarantined_no_name),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(operationLabel(change.operation)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            change.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Row {
            TextButton(onClick = { onRetry(change.outboxId) }) {
                Text(stringResource(R.string.quarantined_retry))
            }
            TextButton(onClick = { onDiscard(change.outboxId) }) {
                Text(stringResource(R.string.quarantined_discard))
            }
        }
    }
}

private fun operationLabel(operation: QuarantinedOperation): Int = when (operation) {
    QuarantinedOperation.CREATE -> R.string.quarantined_op_create
    QuarantinedOperation.UPDATE -> R.string.quarantined_op_update
    QuarantinedOperation.DELETE -> R.string.quarantined_op_delete
    QuarantinedOperation.UNKNOWN -> R.string.quarantined_op_unknown
}

@Composable
private fun ConflictResolutionDialog(
    conflict: ConflictInfo,
    onResolve: (ConflictResolution) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_dialog_title)) },
        text = {
            Column {
                Text(
                    text = conflict.displayName ?: stringResource(R.string.unverified_no_name),
                    style = MaterialTheme.typography.titleSmall
                )
                if (conflict.conflictFields != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.conflict_dialog_fields, conflict.conflictFields),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.conflict_dialog_prompt),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(ConflictResolution.USE_LOCAL) }) {
                Text(stringResource(R.string.conflict_use_local))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(ConflictResolution.USE_SERVER) }) {
                Text(stringResource(R.string.conflict_use_server))
            }
        }
    )
}

/** How often the relative "last sync" time and the overdue check re-evaluate. */
private const val CLOCK_TICK_MILLIS = 30_000L

/** Absolute form of the last-sync time, shown on tap. */
private const val ABSOLUTE_FLAGS =
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
