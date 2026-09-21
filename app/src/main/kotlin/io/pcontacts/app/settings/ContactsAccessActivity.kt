// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.feature.settings.ContactsAccessKind
import io.pcontacts.feature.settings.ContactsAccessScreen

/** Hosts the READ_CONTACTS transparency list for one [ContactsAccessKind]. */
class ContactsAccessActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = ContactsAccessKind.entries[intent.getIntExtra(EXTRA_KIND, 0)]
        val permissionPage = ContactsPermissionPage(this)
        val apps = ContactsAccessApps.list(this, kind)
        setContent {
            PcontactsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ContactsAccessScreen(
                        kind = kind,
                        apps = apps,
                        permissionRoute = permissionPage.route(),
                        onOpenPermission = permissionPage::open,
                        onBack = ::finish
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"

        fun intent(context: Context, kind: ContactsAccessKind): Intent =
            Intent(context, ContactsAccessActivity::class.java).putExtra(EXTRA_KIND, kind.ordinal)
    }
}
