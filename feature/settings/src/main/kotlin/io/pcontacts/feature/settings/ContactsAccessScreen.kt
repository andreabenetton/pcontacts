// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Full-screen list of the apps holding READ_CONTACTS, with the way to
 * the system page where the permission can actually be revoked. The
 * list scrolls; the two buttons stay put at the bottom.
 */
@Composable
fun ContactsAccessScreen(
    kind: ContactsAccessKind,
    apps: List<ContactsAccessApp>,
    permissionRoute: ContactsPermissionRoute,
    onOpenPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val titleRes = when (kind) {
        ContactsAccessKind.USER -> R.string.contacts_access_dialog_title
        ContactsAccessKind.SYSTEM -> R.string.system_contacts_access_dialog_title
    }
    val detailRes = when (kind) {
        ContactsAccessKind.USER -> R.string.contacts_access_detail
        ContactsAccessKind.SYSTEM -> R.string.system_contacts_access_detail
    }
    Scaffold(
        modifier = modifier,
        topBar = { ScreenTopBar(title = stringResource(titleRes), onBack = onBack) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))
                Text(text = stringResource(detailRes), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                AppList(apps)
                Spacer(Modifier.height(16.dp))
            }
            permissionHint(permissionRoute)?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onOpenPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.contacts_access_open_permission))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The remaining taps when the permission page itself is out of reach; null when it is not. */
@Composable
private fun permissionHint(route: ContactsPermissionRoute): String? = when (route) {
    ContactsPermissionRoute.DIRECT -> null
    ContactsPermissionRoute.PERMISSION_MANAGER -> stringResource(R.string.contacts_access_hint_permission_manager)
    ContactsPermissionRoute.PRIVACY_SETTINGS -> stringResource(R.string.contacts_access_hint_privacy_settings)
}

@Composable
private fun AppList(apps: List<ContactsAccessApp>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        apps.forEachIndexed { index, app ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AppRow(app)
        }
    }
}

/** The app's own icon; an initial in a circle only when the host could not load one. */
@Composable
private fun AppIcon(app: ContactsAccessApp) {
    val shape = Modifier.size(36.dp).clip(CircleShape)
    val icon = app.icon
    if (icon != null) {
        Image(bitmap = icon.asImageBitmap(), contentDescription = null, modifier = shape)
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = shape.background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                text = app.appName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun AppRow(app: ContactsAccessApp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        AppIcon(app)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = app.appName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
