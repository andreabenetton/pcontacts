// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.feature.settings.LinkedImportListViewModel
import io.pcontacts.feature.settings.LinkedImportScreen
import io.pcontacts.feature.settings.LinkedImportViewModel

/**
 * Hosts the ADR-0023 import list. Both view models talk to the
 * provider through one [LinkedImportBridge]; the list rescans after
 * every completed import.
 */
class LinkedImportActivity : ComponentActivity() {

    private val bridge by lazy { LinkedImportBridge(applicationContext, ::currentAccount) }
    private val listViewModel by lazy {
        LinkedImportListViewModel(scan = bridge::scan, importMany = bridge::importMany)
    }
    private val importViewModel by lazy {
        LinkedImportViewModel(
            loadPreview = bridge::loadPreview,
            importCandidates = bridge::import
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (currentAccount() == null) {
            finish()
            return
        }
        setContent {
            PcontactsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LinkedImportScreen(
                        listViewModel = listViewModel,
                        importViewModel = importViewModel,
                        onBack = ::finish
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        listViewModel.dispose()
        importViewModel.dispose()
        super.onDestroy()
    }

    private fun currentAccount(): Account? =
        AccountManager.get(this).getAccountsByType(PROTON_ACCOUNT_TYPE).firstOrNull()
}
