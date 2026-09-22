// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.feature.settings.DependenciesScreen

/**
 * Hosts the shipped dependency audit (ADR-0024). CVE pages open through the
 * platform's external-link mechanism (a browser the user chose), never an
 * embedded WebView; when nothing on the device can open a link the screen
 * says so.
 */
class DependenciesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val audit = DependencyAuditAsset.load(this)
        setContent {
            PcontactsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DependenciesScreen(
                        audit = audit,
                        onOpenLink = { url -> startActivityIfAvailable(Intent(Intent.ACTION_VIEW, url.toUri())) },
                        onBack = ::finish
                    )
                }
            }
        }
    }
}
