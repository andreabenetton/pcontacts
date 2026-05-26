// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.auth.LoginActivity
import io.pcontacts.app.notifications.SyncNotifier
import io.pcontacts.app.permissions.ContactsPermissionBanner
import io.pcontacts.app.permissions.ContactsPermissionState
import io.pcontacts.app.permissions.ContactsPermissionStatus
import io.pcontacts.app.settings.SettingsActivity
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.app.verification.HumanVerificationLauncher
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.sync.contacts.SyncBootstrap

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LauncherViewModel
    private var resumeTick = 0
    private var pendingVerificationReturn = false
    private var notificationDenied = false
    private var contactsPermissionStatus by mutableStateOf(ContactsPermissionStatus.GRANTED)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.POST_NOTIFICATIONS] == false) {
            notificationDenied = true
        }
        val prefs = SharedPreferencesUserPreferences(this)
        prefs.contactsPermissionRequested = true
        contactsPermissionStatus = ContactsPermissionState.check(this, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            LauncherViewModel.Factory(
                hasAccount = ::hasProtonAccount,
                loadStatus = { SyncBootstrap.loadLauncherStatus(this@MainActivity) }
            )
        )[LauncherViewModel::class.java]

        requestPermissionsOnce()
        contactsPermissionStatus = ContactsPermissionState.check(
            this, SharedPreferencesUserPreferences(this).contactsPermissionRequested
        )

        setContent {
            PcontactsTheme {
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(
                    snackbarHost = {
                        SnackbarHost(snackbarHostState) { data ->
                            Snackbar(snackbarData = data)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    val state by viewModel.uiState.collectAsState()
                    var tick by remember { mutableIntStateOf(resumeTick) }
                    var showFallbackDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(tick) { viewModel.refresh() }

                    LauncherScreen(
                        state = state,
                        onSignIn = ::launchLogin,
                        onOpenSettings = ::launchSettings,
                        contactsPermissionStatus = contactsPermissionStatus,
                        onGrantContactsPermission = ::handleContactsPermissionAction,
                        modifier = Modifier.padding(innerPadding)
                    )

                    if (showFallbackDialog) {
                        VerificationFallbackDialog(
                            onDismiss = { showFallbackDialog = false }
                        )
                    }

                    LaunchedEffect(Unit) {
                        showFallbackDialog = handleVerificationIntent(intent)
                    }

                    if (notificationDenied) {
                        notificationDenied = false
                        val message = getString(R.string.notification_permission_denied)
                        val action = getString(R.string.notification_permission_settings)
                        LaunchedEffect(Unit) {
                            val result = snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = action,
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                openAppNotificationSettings()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        viewModel.refresh()
        contactsPermissionStatus = ContactsPermissionState.check(
            this, SharedPreferencesUserPreferences(this).contactsPermissionRequested
        )

        if (pendingVerificationReturn) {
            pendingVerificationReturn = false
            requestExpeditedSync()
        }
    }

    private fun handleVerificationIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(SyncNotifier.EXTRA_VERIFICATION_NEEDED, false) != true) {
            return false
        }
        val url = intent.getStringExtra(SyncNotifier.EXTRA_VERIFICATION_URL)
        intent.removeExtra(SyncNotifier.EXTRA_VERIFICATION_NEEDED)
        intent.removeExtra(SyncNotifier.EXTRA_VERIFICATION_URL)

        if (url != null) {
            pendingVerificationReturn = true
            HumanVerificationLauncher.launch(this, url)
            return false
        }
        return true
    }

    private fun requestExpeditedSync() {
        val account = AccountManager.get(this)
            .getAccountsByType(PROTON_ACCOUNT_TYPE)
            .firstOrNull() ?: return
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
    }

    private fun requestPermissionsOnce() {
        val prefs = SharedPreferencesUserPreferences(this)
        val perms = mutableListOf<String>()

        val contactsNotGranted =
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED
        if (!prefs.contactsPermissionRequested && contactsNotGranted) {
            perms += Manifest.permission.READ_CONTACTS
            perms += Manifest.permission.WRITE_CONTACTS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !prefs.notificationPermissionRequested &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }

        if (perms.isEmpty()) return

        prefs.contactsPermissionRequested = true
        prefs.notificationPermissionRequested = true
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun handleContactsPermissionAction() {
        if (contactsPermissionStatus == ContactsPermissionStatus.PERMANENTLY_DENIED) {
            openAppSettings()
        } else {
            permissionLauncher.launch(ContactsPermissionState.requiredPermissions())
        }
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun hasProtonAccount(): Boolean =
        AccountManager.get(this).getAccountsByType(PROTON_ACCOUNT_TYPE).isNotEmpty()

    private fun launchLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun launchSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}

@Composable
internal fun LauncherScreen(
    state: LauncherUiState,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    contactsPermissionStatus: ContactsPermissionStatus = ContactsPermissionStatus.GRANTED,
    onGrantContactsPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(8.dp))

        when (state) {
            is LauncherUiState.Loading -> {
                Text(
                    text = stringResource(R.string.launcher_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            is LauncherUiState.NoAccount -> {
                Text(
                    text = stringResource(R.string.launcher_no_account),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.launcher_sign_in))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.launcher_settings))
                }
            }

            is LauncherUiState.SignedIn -> {
                SignedInStatus(state.status)
                if (contactsPermissionStatus != ContactsPermissionStatus.GRANTED) {
                    Spacer(Modifier.height(16.dp))
                    ContactsPermissionBanner(
                        isPermanentlyDenied = contactsPermissionStatus == ContactsPermissionStatus.PERMANENTLY_DENIED,
                        onAction = onGrantContactsPermission
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.launcher_settings_detail))
                }
            }
        }
    }
}

@Composable
private fun SignedInStatus(status: io.pcontacts.core.sync.contacts.LauncherStatus) {
    Text(
        text = stringResource(R.string.launcher_signed_in),
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.launcher_synced_contacts, status.totalContacts),
        style = MaterialTheme.typography.bodyMedium
    )
    val lastSyncMillis = status.lastSyncedAtMillis
    val lastSyncText = if (lastSyncMillis != null) {
        stringResource(
            R.string.launcher_last_sync,
            DateUtils.getRelativeTimeSpanString(
                lastSyncMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
        )
    } else {
        stringResource(R.string.launcher_last_sync_never)
    }
    Text(
        text = lastSyncText,
        style = MaterialTheme.typography.bodyMedium
    )
    if (status.pendingChanges > 0) {
        Text(
            text = pluralStringResource(R.plurals.launcher_pending_changes, status.pendingChanges, status.pendingChanges),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (status.quarantinedChanges > 0) {
        Text(
            text = pluralStringResource(R.plurals.launcher_quarantined_changes, status.quarantinedChanges, status.quarantinedChanges),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (status.unverifiedContacts > 0) {
        Text(
            text = stringResource(R.string.launcher_unverified_warning, status.unverifiedContacts),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun VerificationFallbackDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.verification_fallback_title)) },
        text = { Text(stringResource(R.string.verification_fallback_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.verification_fallback_dismiss))
            }
        }
    )
}
