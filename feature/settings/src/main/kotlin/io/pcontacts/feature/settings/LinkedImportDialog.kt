// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Entry button on the Settings screen: the host opens [LinkedImportScreen]. */
@Composable
internal fun LinkedImportSection(enabled: Boolean, onOpen: () -> Unit) {
    ActionButton(enabled = enabled, onClick = onOpen, textRes = R.string.linked_import_button)
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.linked_import_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The dialogs of the single-contact flow: progress while loading or
 * writing, and the review checklist. Outcomes (imported, failed, gone)
 * are not dialogs — the hosting screen reports them in place.
 */
@Composable
internal fun LinkedImportDialog(viewModel: LinkedImportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LinkedImportState.Loading -> ProgressDialog(R.string.linked_import_loading)
        LinkedImportState.Importing -> ProgressDialog(R.string.linked_import_importing)
        is LinkedImportState.Review -> ReviewDialog(
            review = s,
            onToggle = viewModel::toggle,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismiss
        )
        LinkedImportState.Hidden,
        LinkedImportState.NotFound,
        is LinkedImportState.Imported,
        is LinkedImportState.Failed -> Unit
    }
}

@Composable
private fun ProgressDialog(messageRes: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.linked_import_dialog_title)) },
        text = {
            SyncIndicator(
                tone = SyncTone.RUNNING,
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                glyphSize = 20.dp
            )
        },
        confirmButton = {}
    )
}

@Composable
private fun ReviewDialog(
    review: LinkedImportState.Review,
    onToggle: (String) -> Unit,
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
                    if (review.changed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.linked_import_changed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceIcons(candidate.sourceIcons)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The providers' launcher icons at text height, followed by a gap; nothing when there are none. */
@Composable
internal fun SourceIcons(icons: List<Bitmap>) {
    if (icons.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        icons.forEach { icon ->
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(16.dp).clip(CircleShape)
            )
        }
    }
    Spacer(Modifier.width(6.dp))
}

private fun kindLabel(kind: LinkedFieldKind): Int = when (kind) {
    LinkedFieldKind.PHONE -> R.string.linked_import_kind_phone
    LinkedFieldKind.EMAIL -> R.string.linked_import_kind_email
    LinkedFieldKind.ADDRESS -> R.string.linked_import_kind_address
    LinkedFieldKind.ORGANIZATION -> R.string.linked_import_kind_organization
    LinkedFieldKind.NOTE -> R.string.linked_import_kind_note
    LinkedFieldKind.IM -> R.string.linked_import_kind_im
    LinkedFieldKind.BIRTHDAY -> R.string.linked_import_kind_birthday
    LinkedFieldKind.ANNIVERSARY -> R.string.linked_import_kind_anniversary
    LinkedFieldKind.NICKNAME -> R.string.linked_import_kind_nickname
    LinkedFieldKind.WEBSITE -> R.string.linked_import_kind_website
}
