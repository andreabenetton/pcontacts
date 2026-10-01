// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Deletions in their grace period on the status card: listed inline when one, in a dialog when several. */
@Composable
internal fun PendingDeleteRows(deletes: List<PendingDelete>, onCancel: (String) -> Unit) {
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
        deletes.forEach { del -> PendingDeleteLine(del, onCancel) }
    }
}

/** A deletion in its grace period: the contact's name where the provider still has it, and its Cancel. */
@Composable
private fun PendingDeleteLine(delete: PendingDelete, onCancel: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = delete.displayName ?: (delete.protonContactId.take(12) + "..."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onCancel(delete.protonContactId) }) {
            Text(stringResource(R.string.pending_delete_cancel))
        }
    }
}

@Composable
internal fun PendingDeletesDialog(deletes: List<PendingDelete>, onCancel: (String) -> Unit, onDismiss: () -> Unit) {
    // The last cancel empties the list: nothing left to show.
    if (deletes.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.pending_delete_count, deletes.size, deletes.size)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = stringResource(R.string.pending_delete_grace), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                deletes.forEach { del -> PendingDeleteLine(del, onCancel) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.unverified_dialog_close)) }
        }
    )
}
