// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Entry button: the host launches the system contact picker on click. */
@Composable
internal fun LinkedImportSection(enabled: Boolean, onPickContact: () -> Unit) {
    OutlinedButton(
        enabled = enabled,
        onClick = onPickContact,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.linked_import_button))
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.linked_import_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun LinkedImportDialog(viewModel: LinkedImportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LinkedImportState.Hidden -> Unit
        LinkedImportState.Loading -> ProgressDialog(R.string.linked_import_loading)
        LinkedImportState.Importing -> ProgressDialog(R.string.linked_import_importing)
        LinkedImportState.NotFound ->
            MessageDialog(stringResource(R.string.linked_import_not_found), viewModel::dismiss)
        is LinkedImportState.Imported -> MessageDialog(
            message = if (s.created) {
                pluralStringResource(R.plurals.linked_import_created, s.count, s.count)
            } else {
                pluralStringResource(R.plurals.linked_import_done, s.count, s.count)
            },
            onDismiss = viewModel::dismiss
        )
        is LinkedImportState.Failed ->
            MessageDialog(stringResource(R.string.linked_import_failed, s.reason), viewModel::dismiss)
        is LinkedImportState.Review -> ReviewDialog(
            review = s,
            onToggle = viewModel::toggle,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismiss
        )
    }
}

@Composable
private fun ProgressDialog(messageRes: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.linked_import_dialog_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(stringResource(messageRes))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linked_import_dialog_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.linked_import_close))
            }
        }
    )
}

@Composable
private fun ReviewDialog(
    review: LinkedImportState.Review,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val creates = review.preview.createsNewContact
    val name = review.preview.contactName ?: stringResource(R.string.unverified_no_name)
    val titleRes = if (creates) R.string.linked_import_create_title else R.string.linked_import_dialog_title
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (review.preview.candidates.isEmpty()) {
                    val emptyRes = if (creates) R.string.linked_import_create_nothing else R.string.linked_import_nothing
                    Text(stringResource(emptyRes))
                } else {
                    val detailRes = if (creates) R.string.linked_import_create_detail else R.string.linked_import_review_detail
                    Text(text = stringResource(detailRes, name), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    review.preview.candidates.forEach { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            checked = candidate.id in review.selected,
                            onToggle = { onToggle(candidate.id) }
                        )
                    }
                    if (creates && review.selected.isNotEmpty() && !review.canConfirm) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.linked_import_create_needs_contact_field),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (review.preview.candidates.isNotEmpty()) {
                TextButton(onClick = onConfirm, enabled = review.canConfirm) {
                    val confirmRes = if (creates) R.string.linked_import_create_confirm else R.string.linked_import_confirm
                    Text(stringResource(confirmRes))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.linked_import_cancel))
            }
        }
    )
}

@Composable
private fun CandidateRow(candidate: LinkedImportCandidate, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column {
            Text(text = candidate.value, style = MaterialTheme.typography.bodyMedium)
            val kind = stringResource(kindLabel(candidate.kind))
            val detail = candidate.source?.let { stringResource(R.string.linked_import_source, kind, it) } ?: kind
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun kindLabel(kind: LinkedFieldKind): Int = when (kind) {
    LinkedFieldKind.PHONE -> R.string.linked_import_kind_phone
    LinkedFieldKind.EMAIL -> R.string.linked_import_kind_email
    LinkedFieldKind.ADDRESS -> R.string.linked_import_kind_address
    LinkedFieldKind.ORGANIZATION -> R.string.linked_import_kind_organization
    LinkedFieldKind.NOTE -> R.string.linked_import_kind_note
    LinkedFieldKind.IM -> R.string.linked_import_kind_im
}
