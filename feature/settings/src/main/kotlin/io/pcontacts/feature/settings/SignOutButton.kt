// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Sign-out asks first: it removes the Proton contacts from this phone and drops the changes
 * not synced yet, and one tap on a red button was all it took before.
 */
@Composable
internal fun SignOutButton(enabled: Boolean, onSignOut: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    OutlinedButton(
        enabled = enabled,
        onClick = { confirming = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.settings_sign_out))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.sign_out_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onSignOut()
                    }
                ) {
                    Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.sign_out_confirm_cancel))
                }
            }
        )
    }
}
