// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The one colour the app uses for "this gets you in": the counterpart of Sign out's error red. */
private val SignInGreen = Color(0xFF2E9E5B)

/**
 * What the app shows before there is a Proton account: the same
 * Settings shell as when signed in, reduced to the Account section, so
 * signing in and signing out happen in the same place on the same
 * screen. [loading] disables the button while the account state is
 * still being read.
 *
 * While the Contacts permission has not been granted yet — that is,
 * before the first sync could expose anything — the bottom of the
 * screen says who will be able to read the synced contacts and links
 * the de-Googled ROM explanation, so the choice is informed before it
 * is made.
 */
// One parameter per fact the host knows and the screen shows; there is no state to bundle them in.
@Suppress("LongParameterList")
@Composable
fun SignInScreen(
    loading: Boolean,
    contactsPermissionGranted: Boolean,
    onSignIn: () -> Unit,
    onOpenDeGoogledRoms: () -> Unit,
    modifier: Modifier = Modifier,
    /** The account was signed out by the 2.0 secret-store upgrade; say so above the button. */
    storageUpgradeNotice: Boolean = false,
    snackbarHost: @Composable () -> Unit = {},
    /** The shipped dependency audit's status (ADR-0024), shown next to the version. */
    audit: AuditIndicator? = null,
    onOpenRepository: (() -> Unit)? = null
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopBar(audit, onOpenRepository) },
        snackbarHost = snackbarHost
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            SectionHeader(R.string.settings_section_account)
            Text(
                text = stringResource(R.string.settings_sign_in_detail),
                style = MaterialTheme.typography.bodyMedium
            )
            if (storageUpgradeNotice) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sign_in_storage_upgrade_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                enabled = !loading,
                onClick = onSignIn,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SignInGreen),
                border = BorderStroke(2.dp, SignInGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_sign_in))
            }
            Spacer(Modifier.weight(1f))
            if (!contactsPermissionGranted) {
                LinkedText(
                    templateRes = R.string.sign_in_rom_notice,
                    phraseRes = R.string.de_googled_rom_link,
                    onClick = onOpenDeGoogledRoms,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = stringResource(R.string.sign_in_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
