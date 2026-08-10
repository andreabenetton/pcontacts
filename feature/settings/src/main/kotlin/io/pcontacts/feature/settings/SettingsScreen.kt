// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncInterval by viewModel.syncInterval.collectAsStateWithLifecycle()
    val busy = state is SettingsUiState.Syncing || state is SettingsUiState.SigningOut

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(24.dp))

        Button(
            enabled = !busy,
            onClick = viewModel::triggerSyncNow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_sync_now))
        }
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            enabled = !busy,
            onClick = viewModel::triggerSignOut,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_sign_out))
        }

        Spacer(Modifier.height(24.dp))

        SyncIntervalSelector(
            selected = syncInterval,
            onSelected = viewModel::setSyncInterval,
            enabled = !busy
        )

        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            SettingsUiState.Idle -> Unit
            SettingsUiState.Syncing, SettingsUiState.SigningOut -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator()
            }
            is SettingsUiState.SyncDone -> Text(
                text = s.message,
                style = MaterialTheme.typography.bodyMedium
            )
            is SettingsUiState.SyncFailed -> Text(
                text = stringResource(R.string.settings_sync_failed, s.reason),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            SettingsUiState.SignedOut -> {
                Text(stringResource(R.string.settings_signed_out), style = MaterialTheme.typography.bodyMedium)
                LaunchedEffect(Unit) { onSignedOut() }
            }
            is SettingsUiState.SignOutFailed -> Text(
                text = stringResource(R.string.settings_sign_out_failed, s.reason),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SettingsStatusSection(viewModel)
    }
}

/**
 * The stack of status banners and the dialogs they open. Split out of
 * [SettingsScreen] so neither function carries the branching of all of
 * them at once.
 */
@Composable
private fun SettingsStatusSection(viewModel: SettingsViewModel) {
    val verificationStats by viewModel.verificationStats.collectAsStateWithLifecycle()
    val unverifiedContacts by viewModel.unverifiedContacts.collectAsStateWithLifecycle()
    val unverifiedDialogOpen by viewModel.unverifiedDialogOpen.collectAsStateWithLifecycle()
    val outboxStats by viewModel.outboxStats.collectAsStateWithLifecycle()
    val pendingDeletes by viewModel.pendingDeletes.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val quarantinedChanges by viewModel.quarantinedChanges.collectAsStateWithLifecycle()
    val quarantinedDialogOpen by viewModel.quarantinedDialogOpen.collectAsStateWithLifecycle()

    verificationStats?.let { stats ->
        if (stats.unverifiedContacts > 0) {
            Spacer(Modifier.height(16.dp))
            VerificationWarningBanner(
                stats = stats,
                onClick = viewModel::showUnverifiedContactsDialog
            )
        }
    }

    if (unverifiedDialogOpen) {
        UnverifiedContactsDialog(
            contacts = unverifiedContacts,
            onOpenContact = viewModel::openUnverifiedContactInSystem,
            onDismiss = viewModel::dismissUnverifiedContactsDialog
        )
    }

    if (outboxStats.pending > 0 || outboxStats.quarantined > 0) {
        Spacer(Modifier.height(16.dp))
        OutboxStatusBanner(
            stats = outboxStats,
            onQuarantinedClick = viewModel::showQuarantinedChangesDialog
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

    if (pendingDeletes.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        PendingDeleteBanner(
            deletes = pendingDeletes,
            onCancel = viewModel::cancelPendingDelete
        )
    }

    if (conflicts.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        ConflictBanner(
            conflicts = conflicts,
            onResolve = viewModel::resolveContactConflict
        )
    }

    ContactsAccessSection(viewModel)
}

/** The two READ_CONTACTS transparency banners and their dialogs. */
@Composable
private fun ContactsAccessSection(viewModel: SettingsViewModel) {
    val contactsAccessApps by viewModel.contactsAccessApps.collectAsStateWithLifecycle()
    val contactsAccessDialogOpen by viewModel.contactsAccessDialogOpen.collectAsStateWithLifecycle()
    val systemContactsAccessApps by viewModel.systemContactsAccessApps.collectAsStateWithLifecycle()
    val systemContactsAccessDialogOpen by viewModel.systemContactsAccessDialogOpen.collectAsStateWithLifecycle()

    if (contactsAccessApps.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        ContactsAccessBanner(
            apps = contactsAccessApps,
            onClick = viewModel::showContactsAccessDialog
        )
    }

    if (contactsAccessDialogOpen) {
        ContactsAccessDialog(
            apps = contactsAccessApps,
            onDismiss = viewModel::dismissContactsAccessDialog
        )
    }

    if (systemContactsAccessApps.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        SystemContactsAccessBanner(
            apps = systemContactsAccessApps,
            onClick = viewModel::showSystemContactsAccessDialog
        )
    }

    if (systemContactsAccessDialogOpen) {
        SystemContactsAccessDialog(
            apps = systemContactsAccessApps,
            onDismiss = viewModel::dismissSystemContactsAccessDialog
        )
    }
}

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

@Composable
private fun VerificationWarningBanner(stats: VerificationStats, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.verification_icon),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = stringResource(R.string.verification_warning, stats.unverifiedContacts, stats.totalContacts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.verification_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.verification_tap_to_review),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenContact(c.rawContactId) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = c.displayName
                                        ?: stringResource(R.string.unverified_no_name),
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.unverified_dialog_close))
            }
        }
    )
}

@Composable
private fun OutboxStatusBanner(stats: OutboxStats, onQuarantinedClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp)
    ) {
        if (stats.pending > 0) {
            Text(
                text = pluralStringResource(R.plurals.outbox_pending, stats.pending, stats.pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        if (stats.quarantined > 0) {
            Column(modifier = Modifier.clickable(onClick = onQuarantinedClick)) {
                Text(
                    text = pluralStringResource(R.plurals.outbox_quarantined, stats.quarantined, stats.quarantined),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.quarantined_tap_to_review),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
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
private fun PendingDeleteBanner(
    deletes: List<PendingDelete>,
    onCancel: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(12.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.pending_delete_count, deletes.size, deletes.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Text(
            text = stringResource(R.string.pending_delete_grace),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Spacer(Modifier.height(8.dp))
        deletes.forEach { del ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = del.protonContactId.take(12) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
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
private fun ConflictBanner(
    conflicts: List<ConflictInfo>,
    onResolve: (String, ConflictResolution) -> Unit
) {
    var selectedConflict by remember { mutableStateOf<ConflictInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.conflict_count, conflicts.size, conflicts.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            text = stringResource(R.string.conflict_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(Modifier.height(8.dp))
        conflicts.forEach { conflict ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conflict.displayName ?: conflict.protonContactId.take(12),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
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

@Composable
private fun ContactsAccessBanner(apps: List<ContactsAccessApp>, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.contacts_access_count, apps.size, apps.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.contacts_access_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.contacts_access_tap_to_review),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ContactsAccessDialog(
    apps: List<ContactsAccessApp>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_access_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.contacts_access_detail),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                apps.forEach { app ->
                    Text(
                        text = "${app.appName} (${app.packageName})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
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

@Composable
private fun SystemContactsAccessBanner(apps: List<ContactsAccessApp>, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onClick)
            .padding(12.dp)
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

@Composable
private fun SystemContactsAccessDialog(
    apps: List<ContactsAccessApp>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.system_contacts_access_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.system_contacts_access_detail),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                apps.forEach { app ->
                    Text(
                        text = "${app.appName} (${app.packageName})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
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

@Composable
private fun ConflictResolutionDialog(
    conflict: ConflictInfo,
    onResolve: (ConflictResolution) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.conflict_dialog_title))
        },
        text = {
            Column {
                Text(
                    text = conflict.displayName ?: stringResource(R.string.settings_title),
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
