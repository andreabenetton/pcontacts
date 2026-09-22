// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.pcontacts.app.account.LogoutHelper
import io.pcontacts.app.account.MissingContactsPermissionException
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.advisories.AdvisoryBootstrap
import io.pcontacts.app.auth.LoginActivity
import io.pcontacts.app.logging.AndroidLogcatSink
import io.pcontacts.app.notifications.SyncNotifier
import io.pcontacts.app.notifications.VulnerabilityNotice
import io.pcontacts.app.permissions.ContactsPermissionBanner
import io.pcontacts.app.permissions.ContactsPermissionState
import io.pcontacts.app.permissions.ContactsPermissionStatus
import io.pcontacts.app.settings.DeGoogledRomsActivity
import io.pcontacts.app.settings.DependenciesActivity
import io.pcontacts.app.settings.DependencyAuditAsset
import io.pcontacts.app.settings.REPOSITORY_URL
import io.pcontacts.app.settings.SettingsHost
import io.pcontacts.app.settings.startActivityIfAvailable
import io.pcontacts.app.sync.SyncRequests
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.app.verification.HumanVerificationLauncher
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.sync.AuthBootstrap
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.feature.settings.AuditIndicator
import io.pcontacts.feature.settings.SettingsScreen
import io.pcontacts.feature.settings.SignInScreen
import kotlinx.coroutines.launch

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
    private var upgradeSignOutRunning by mutableStateOf(false)
    private var storageUpgradeNotice by mutableStateOf(false)

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
        // The sync requested at sign-in could not write without Contacts access; run it now —
        // unless the upgrade sign-out that waited for this access takes the account away first.
        if (contactsPermissionStatus == ContactsPermissionStatus.GRANTED && !signOutAfterStorageUpgradeIfNeeded()) {
            requestExpeditedSync()
        }
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
        signOutAfterStorageUpgradeIfNeeded()
        VulnerabilityNotice.postIfOpen(this)
        val audit = DependencyAuditAsset.load(this)
        val runtime = AdvisoryBootstrap.state(this)
        val signedOutStatus = if (runtime.enabled) audit.withRuntime(runtime).status else null
        val openDependencies = { startActivity(Intent(this, DependenciesActivity::class.java)) }

        setContent {
            PcontactsTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val state by viewModel.uiState.collectAsState()
                val tick by remember { mutableIntStateOf(resumeTick) }
                var showFallbackDialog by remember { mutableStateOf(false) }

                LaunchedEffect(tick) { viewModel.refresh() }

                if (state is LauncherUiState.SignedIn && !upgradeSignOutRunning) {
                    SettingsScreen(
                        viewModel = settingsHost.viewModel,
                        actions = settingsHost.actions(),
                        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
                        banner = {
                            if (contactsPermissionStatus != ContactsPermissionStatus.GRANTED) {
                                ContactsPermissionBanner(
                                    isPermanentlyDenied = contactsPermissionStatus == ContactsPermissionStatus.PERMANENTLY_DENIED,
                                    onAction = ::handleContactsPermissionAction,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        },
                        audit = audit
                    )
                } else {
                    SignInScreen(
                        loading = state is LauncherUiState.Loading || upgradeSignOutRunning,
                        contactsPermissionGranted = contactsPermissionStatus == ContactsPermissionStatus.GRANTED,
                        onSignIn = ::launchLogin,
                        onOpenDeGoogledRoms = { startActivity(Intent(this, DeGoogledRomsActivity::class.java)) },
                        storageUpgradeNotice = storageUpgradeNotice,
                        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
                        audit = AuditIndicator(signedOutStatus, openDependencies),
                        onOpenRepository = ::openRepository
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
        storageUpgradeNotice = SharedPreferencesUserPreferences(this).secretsStorageUpgraded
        // Permissions are asked for only once there is an account to sync: a first launch shows the
        // sign-in screen undisturbed, and the prompts follow the return from LoginActivity. The
        // prefs flags inside make repeated resumes a no-op.
        if (hasProtonAccount()) requestPermissionsOnce()
        val hadContactsAccess = contactsPermissionStatus == ContactsPermissionStatus.GRANTED
        contactsPermissionStatus = ContactsPermissionState.check(
            this, SharedPreferencesUserPreferences(this).contactsPermissionRequested
        )
        // Access granted while we were away (the system Settings page): sync as soon as we can.
        val gainedContactsAccess = !hadContactsAccess && contactsPermissionStatus == ContactsPermissionStatus.GRANTED
        // The upgrade sign-out needs Contacts access; if it was skipped for lack of it, now is the time.
        val signingOut = gainedContactsAccess && signOutAfterStorageUpgradeIfNeeded()
        if (gainedContactsAccess && !signingOut) requestExpeditedSync()
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

    /** A prompt sync after a grant or a verification return; the user's Android sync switch still wins. */
    private fun requestExpeditedSync() {
        val account = AccountManager.get(this)
            .getAccountsByType(PROTON_ACCOUNT_TYPE)
            .firstOrNull() ?: return
        SyncRequests.requestIfEnabled(account)
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
        // The next sign-in asks for the permissions again instead of going straight to the banner.
        SharedPreferencesUserPreferences(this).contactsPermissionRequested = false
        viewModel.refresh()
    }

    private fun hasProtonAccount(): Boolean =
        AccountManager.get(this).getAccountsByType(PROTON_ACCOUNT_TYPE).isNotEmpty()

    /**
     * First start after a 1.x install: the old session went with its purged secret file (ADR-0009)
     * and cannot be carried over, so the stale account is signed out here, before the screen is
     * decided, and the sign-in screen says why. Without Contacts access the sign-out cannot run;
     * the account then stays until the permission arrives (in-app grant or the system page).
     * Returns true when a sign-out was started.
     */
    private fun signOutAfterStorageUpgradeIfNeeded(): Boolean {
        if (upgradeSignOutRunning) return false
        val account = AccountManager.get(this).getAccountsByType(PROTON_ACCOUNT_TYPE).firstOrNull() ?: return false
        if (!AuthBootstrap.storageUpgradePending(this)) return false
        storageUpgradeNotice = true
        upgradeSignOutRunning = true
        lifecycleScope.launch {
            try {
                LogoutHelper(this@MainActivity).signOut(account)
            } catch (e: MissingContactsPermissionException) {
                RedactingLogger(tag = "Main", sink = AndroidLogcatSink())
                    .warn(e) { "storage-upgrade sign-out skipped: no Contacts access" }
            }
            upgradeSignOutRunning = false
            onSignedOutFromSettings()
        }
        return true
    }

    private fun openRepository() {
        startActivityIfAvailable(Intent(Intent.ACTION_VIEW, REPOSITORY_URL.toUri()))
    }

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
