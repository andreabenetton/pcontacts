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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.auth.LoginActivity
import io.pcontacts.app.notifications.SyncNotifier
import io.pcontacts.app.permissions.ContactsPermissionBanner
import io.pcontacts.app.permissions.ContactsPermissionState
import io.pcontacts.app.permissions.ContactsPermissionStatus
import io.pcontacts.app.settings.SettingsHost
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.app.verification.HumanVerificationLauncher
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.feature.settings.SettingsScreen
import io.pcontacts.feature.settings.SignInScreen

/**
 * The one screen of the app: the Settings shell reduced to its Account
 * section while there is no Proton account, the full Settings screen
 * (status card on top) once there is. Also owns first-run permission requests and the return from the
 * human-verification web flow.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LauncherViewModel
    private var resumeTick = 0
    private var pendingVerificationReturn = false
    private var notificationDenied = false
    private var contactsPermissionStatus by mutableStateOf(ContactsPermissionStatus.GRANTED)

    private val settingsHost: SettingsHost = SettingsHost(this, ::onSignedOutFromSettings)

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

        contactsPermissionStatus = ContactsPermissionState.check(
            this, SharedPreferencesUserPreferences(this).contactsPermissionRequested
        )

        setContent {
            PcontactsTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val state by viewModel.uiState.collectAsState()
                val tick by remember { mutableIntStateOf(resumeTick) }
                var showFallbackDialog by remember { mutableStateOf(false) }

                LaunchedEffect(tick) { viewModel.refresh() }

                if (state is LauncherUiState.SignedIn) {
                    SettingsScreen(
                        viewModel = settingsHost.viewModel,
                        actions = settingsHost.actions(onBack = null),
                        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
                        banner = {
                            if (contactsPermissionStatus != ContactsPermissionStatus.GRANTED) {
                                ContactsPermissionBanner(
                                    isPermanentlyDenied = contactsPermissionStatus == ContactsPermissionStatus.PERMANENTLY_DENIED,
                                    onAction = ::handleContactsPermissionAction,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        }
                    )
                } else {
                    SignInScreen(
                        loading = state is LauncherUiState.Loading,
                        onSignIn = ::launchLogin,
                        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } }
                    )
                }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        viewModel.refresh()
        // Permissions are asked for only once there is an account to sync: a first launch shows the
        // sign-in screen undisturbed, and the prompts follow the return from LoginActivity. The
        // prefs flags inside make repeated resumes a no-op.
        if (hasProtonAccount()) requestPermissionsOnce()
        contactsPermissionStatus = ContactsPermissionState.check(
            this, SharedPreferencesUserPreferences(this).contactsPermissionRequested
        )
        settingsHost.onResume()

        if (pendingVerificationReturn) {
            pendingVerificationReturn = false
            requestExpeditedSync()
        }
    }

    override fun onPause() {
        super.onPause()
        settingsHost.onPause()
    }

    override fun onDestroy() {
        settingsHost.dispose()
        super.onDestroy()
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

    /** Back to the sign-in prompt; the settings view model forgets its "signed out" state for the next login. */
    private fun onSignedOutFromSettings() {
        settingsHost.viewModel.reset()
        viewModel.refresh()
    }

    private fun hasProtonAccount(): Boolean =
        AccountManager.get(this).getAccountsByType(PROTON_ACCOUNT_TYPE).isNotEmpty()

    private fun launchLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
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
