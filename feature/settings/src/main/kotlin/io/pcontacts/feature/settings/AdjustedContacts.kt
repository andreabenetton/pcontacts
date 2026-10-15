// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Values pcontacts fitted to Proton's editor rules before sending (ADR-0017, 2026-10-15), and their dialog. */
@Composable
internal fun AdjustedContactsRow(viewModel: SettingsViewModel) {
    val adjusted by viewModel.adjustedContacts.collectAsStateWithLifecycle()
    val dialogOpen by viewModel.adjustedDialogOpen.collectAsStateWithLifecycle()
    if (adjusted.isNotEmpty()) {
        StatusRow(
            text = pluralStringResource(R.plurals.adjusted_count, adjusted.size, adjusted.size),
            detail = stringResource(R.string.quarantined_tap_to_review),
            error = false,
            onClick = viewModel::showAdjustedContactsDialog
        )
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAdjustedContactsDialog,
            title = { Text(stringResource(R.string.adjusted_dialog_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = stringResource(R.string.adjusted_detail), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    adjusted.forEach { item ->
                        AdjustedContactRow(item, viewModel::openQuarantinedContactInSystem, viewModel::dismissAdjusted)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissAdjustedContactsDialog) {
                    Text(stringResource(R.string.quarantined_dialog_close))
                }
            }
        )
    }
}

@Composable
private fun AdjustedContactRow(
    item: AdjustedContact,
    onOpenContact: (Long) -> Unit,
    onDismiss: (AdjustedContact) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        val open = item.rawContactId?.let { id -> Modifier.clickable { onOpenContact(id) } } ?: Modifier
        Column(modifier = Modifier.fillMaxWidth().then(open)) {
            Text(
                text = item.displayName ?: stringResource(R.string.quarantined_no_name),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(adjustmentLabel(item.kind)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { onDismiss(item) }) {
            Text(stringResource(R.string.adjusted_dismiss))
        }
    }
}

internal fun adjustmentLabel(kind: ProtonAdjustmentKind): Int = when (kind) {
    ProtonAdjustmentKind.NAME_SHORTENED -> R.string.adjusted_name_shortened
    ProtonAdjustmentKind.FIRST_NAME_SHORTENED -> R.string.adjusted_first_name_shortened
    ProtonAdjustmentKind.LAST_NAME_SHORTENED -> R.string.adjusted_last_name_shortened
    ProtonAdjustmentKind.EMAIL_TRIMMED -> R.string.adjusted_email_trimmed
}
