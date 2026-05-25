// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.permissions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.pcontacts.app.R

enum class ContactsPermissionStatus {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED
}

object ContactsPermissionState {

    fun check(activity: ComponentActivity, hasBeenRequested: Boolean): ContactsPermissionStatus {
        val readGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (readGranted && writeGranted) return ContactsPermissionStatus.GRANTED
        if (hasBeenRequested &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
        ) {
            return ContactsPermissionStatus.PERMANENTLY_DENIED
        }
        return ContactsPermissionStatus.DENIED
    }

    fun requiredPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )
}

@Composable
fun ContactsPermissionBanner(
    isPermanentlyDenied: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.contacts_permission_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAction) {
                Text(
                    text = if (isPermanentlyDenied) {
                        stringResource(R.string.contacts_permission_open_settings)
                    } else {
                        stringResource(R.string.contacts_permission_grant)
                    }
                )
            }
        }
    }
}
