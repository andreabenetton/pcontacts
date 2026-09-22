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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.pcontacts.app.advisories.AdvisoryBootstrap
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.feature.settings.AdvisoryCheckState
import io.pcontacts.feature.settings.DependenciesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Hosts the shipped dependency audit (ADR-0024). CVE pages open through the
 * platform's external-link mechanism (a browser the user chose), never an
 * embedded WebView; when nothing on the device can open a link the screen
 * says so.
 */
class DependenciesActivity : ComponentActivity() {

    private var runtime by mutableStateOf(AdvisoryCheckState.OFF)
    private var checking by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val audit = DependencyAuditAsset.load(this)
        runtime = AdvisoryBootstrap.state(this)
        setContent {
            PcontactsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DependenciesScreen(
                        audit = audit,
                        onOpenLink = { url -> startActivityIfAvailable(Intent(Intent.ACTION_VIEW, url.toUri())) },
                        onBack = ::finish,
                        runtime = runtime,
                        checking = checking,
                        onCheckNow = ::checkNow,
                        onMute = { advisory, muted -> runtime = AdvisoryBootstrap.setMuted(this, advisory, muted) }
                    )
                }
            }
        }
    }

    /** A failed check (offline, osv.dev down, an answer of the wrong shape) leaves the last state on screen. */
    private fun checkNow() {
        if (checking) return
        checking = true
        lifecycleScope.launch {
            try {
                runtime = AdvisoryBootstrap.runCheck(this@DependenciesActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // keep the previous state
            } catch (_: IllegalArgumentException) {
                // keep the previous state
            }
            checking = false
        }
    }
}
