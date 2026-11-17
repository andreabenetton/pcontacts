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

/**
 * The signature rows and their lists: contacts whose signature failed (an error), and contacts
 * Proton holds without one (neutral; review 2026-11-17, F7).
 */
@Composable
internal fun SignatureRows(viewModel: SettingsViewModel) {
    val verificationStats by viewModel.verificationStats.collectAsStateWithLifecycle()
    val unverifiedContacts by viewModel.unverifiedContacts.collectAsStateWithLifecycle()
    val unverifiedDialogOpen by viewModel.unverifiedDialogOpen.collectAsStateWithLifecycle()
    val unsignedContacts by viewModel.unsignedContacts.collectAsStateWithLifecycle()
    val unsignedDialogOpen by viewModel.unsignedDialogOpen.collectAsStateWithLifecycle()

    verificationStats?.takeIf { it.unverifiedContacts > 0 }?.let { stats ->
        StatusRow(
            text = stringResource(R.string.verification_warning, stats.unverifiedContacts, stats.totalContacts),
            detail = stringResource(R.string.verification_tap_to_review),
            error = true,
            onClick = viewModel::showUnverifiedContactsDialog
        )
    }
    if (unsignedContacts.isNotEmpty()) {
        StatusRow(
            text = pluralStringResource(R.plurals.unsigned_contacts, unsignedContacts.size, unsignedContacts.size),
            detail = stringResource(R.string.verification_tap_to_review),
            error = false,
            onClick = viewModel::showUnsignedContactsDialog
        )
    }
    if (unverifiedDialogOpen) {
        ContactListDialog(
            texts = ContactListTexts(
                title = stringResource(R.string.unverified_dialog_title),
                detail = stringResource(R.string.verification_detail),
                empty = stringResource(R.string.unverified_dialog_empty)
            ),
            contacts = unverifiedContacts,
            onOpenContact = viewModel::openUnverifiedContactInSystem,
            onDismiss = viewModel::dismissUnverifiedContactsDialog
        )
    }
    if (unsignedDialogOpen) {
        ContactListDialog(
            texts = ContactListTexts(
                title = stringResource(R.string.unsigned_dialog_title),
                detail = stringResource(R.string.unsigned_dialog_detail),
                empty = stringResource(R.string.unsigned_dialog_empty)
            ),
            contacts = unsignedContacts,
            onOpenContact = viewModel::openUnverifiedContactInSystem,
            onDismiss = viewModel::dismissUnsignedContactsDialog
        )
    }
}

private data class ContactListTexts(val title: String, val detail: String, val empty: String)

/** A list of contacts that open in the system app: the unverified ones, or the unsigned ones. */
@Composable
private fun ContactListDialog(
    texts: ContactListTexts,
    contacts: List<UnverifiedContactSummary>,
    onOpenContact: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(texts.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = texts.detail,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                if (contacts.isEmpty()) {
                    Text(
                        text = texts.empty,
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
