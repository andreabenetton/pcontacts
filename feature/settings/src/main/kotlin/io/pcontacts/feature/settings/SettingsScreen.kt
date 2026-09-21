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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings, in four sections: Sync (status card, Sync now, interval),
 * Contacts (storage, linked import), Privacy (READ_CONTACTS lists) and
 * Account (Sign out). [banner] is the host's slot above the sections,
 * used for the missing-permission notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncInterval by viewModel.syncInterval.collectAsStateWithLifecycle()
    val syncRunning by viewModel.syncRunning.collectAsStateWithLifecycle()
    val actionInFlight = state is SettingsUiState.Syncing || state is SettingsUiState.SigningOut
    val busy = actionInFlight || syncRunning

    Scaffold(
        modifier = modifier,
        topBar = { ScreenTopBar(title = stringResource(R.string.settings_title), onBack = actions.onBack) }
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
            SyncStatusCard(viewModel, state, syncRunning, actions.onSignedOut)
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = !busy,
                onClick = viewModel::triggerSyncNow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_sync_now))
            }
            Spacer(Modifier.height(12.dp))
            SyncIntervalSelector(
                selected = syncInterval,
                onSelected = viewModel::setSyncInterval,
                enabled = !busy
            )

            SectionHeader(R.string.settings_section_contacts)
            actions.onOpenContactsStorage?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_contacts_storage))
                }
                Spacer(Modifier.height(12.dp))
            }
            LinkedImportSection(enabled = !busy, onOpen = actions.onOpenLinkedImport)

            ContactsAccessSection(viewModel, actions)

            SectionHeader(R.string.settings_section_account)
            OutlinedButton(
                enabled = !busy,
                onClick = viewModel::triggerSignOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_sign_out))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(titleRes: Int) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}

// ---- Sync status card ----

private class Tone(val icon: ImageVector, val tint: Color, val inProgress: Boolean)

private class Headline(val text: String, val tone: Tone)

/** One line that says what the sync is doing right now, in priority order. */
@Composable
private fun headline(state: SettingsUiState, syncRunning: Boolean, lastSync: LastSyncSummary?): Headline {
    val scheme = MaterialTheme.colorScheme
    val ok = Tone(Icons.Default.Check, scheme.primary, inProgress = false)
    val warn = Tone(Icons.Default.Warning, scheme.error, inProgress = false)
    val running = Tone(Icons.Default.Refresh, scheme.primary, inProgress = true)
    val info = Tone(Icons.Default.Info, scheme.onSurfaceVariant, inProgress = false)
    return when {
        state is SettingsUiState.SigningOut -> Headline(stringResource(R.string.settings_signing_out), running)
        state is SettingsUiState.SignedOut -> Headline(stringResource(R.string.settings_signed_out), ok)
        state is SettingsUiState.SignOutFailed ->
            Headline(stringResource(R.string.settings_sign_out_failed, state.reason), warn)
        state is SettingsUiState.Syncing || syncRunning ->
            Headline(stringResource(R.string.settings_sync_running), running)
        state is SettingsUiState.SyncFailed ->
            Headline(stringResource(R.string.settings_sync_failed, state.reason), warn)
        lastSync?.failureMessage != null -> Headline(lastSync.failureMessage, warn)
        lastSync?.syncedAtMillis == null -> Headline(stringResource(R.string.sync_state_never), info)
        else -> Headline(stringResource(R.string.sync_state_ok), ok)
    }
}

/**
 * Everything about the sync in one place: the current state, when it
 * last ran, and the things that need attention (unverified contacts,
 * pending or failed changes, scheduled deletions, conflicts) as rows.
 */
@Composable
private fun SyncStatusCard(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    syncRunning: Boolean,
    onSignedOut: () -> Unit
) {
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val headline = headline(state, syncRunning, lastSync)
    if (state is SettingsUiState.SignedOut) LaunchedEffect(Unit) { onSignedOut() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = headline.tone.icon, contentDescription = null, tint = headline.tone.tint)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = headline.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = headline.tone.tint
                    )
                    lastSync?.let { LastSyncLine(it) }
                }
            }
            if (headline.tone.inProgress) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        StatusRows(viewModel)
    }
}

/** When the last completed run happened and how many contacts it could not sync. */
@Composable
private fun LastSyncLine(info: LastSyncSummary) {
    val syncedAt = info.syncedAtMillis
    val text = if (syncedAt != null) {
        stringResource(
            R.string.settings_last_sync,
            DateUtils.getRelativeTimeSpanString(syncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        )
    } else {
        stringResource(R.string.settings_last_sync_never)
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            onClick = { actions.onOpenContactsAccess(ContactsAccessKind.SYSTEM) }
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

@Composable
private fun SystemContactsAccessBanner(apps: List<ContactsAccessApp>, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.system_contacts_access_count, apps.size, apps.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            text = stringResource(R.string.contacts_access_tap_to_review),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

// ---- Sync interval ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncIntervalSelector(
    selected: SyncInterval,
    onSelected: (SyncInterval) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_sync_interval),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it }
        ) {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                SyncInterval.entries.forEach { interval ->
                    DropdownMenuItem(
                        text = { Text(interval.label) },
                        onClick = {
                            onSelected(interval)
                            expanded = false
                        }
                    )
                }
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
    onRetry: (Long) -> Unit,
    onDiscard: (Long) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
