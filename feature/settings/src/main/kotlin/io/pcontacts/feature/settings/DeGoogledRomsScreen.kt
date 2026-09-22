// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What "de-Googled ROM" means, followed by one card per project in
 * [DE_GOOGLED_ROMS]. [onOpenWebsite] hands the URL to the host, which
 * opens it with the platform's external-link mechanism (never an
 * embedded WebView); it returns false when nothing on the device can,
 * and the screen says so instead of failing.
 */
@Composable
fun DeGoogledRomsScreen(
    onOpenWebsite: (String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.rom_no_browser)
    val open: (String) -> Unit = { url ->
        if (!onOpenWebsite(url)) scope.launch { snackbarHostState.showSnackbar(noBrowser) }
    }
    Scaffold(
        modifier = modifier,
        topBar = { ScreenTopBar(title = stringResource(R.string.rom_screen_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            item { Intro() }
            items(DE_GOOGLED_ROMS, key = { it.name }) { rom ->
                RomCard(rom = rom, onOpenWebsite = open)
                Spacer(Modifier.height(12.dp))
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun Intro() {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.rom_intro_definition), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.rom_intro_approaches), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.rom_intro_security), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
    }
}

/** One project: name, the four attributes, an optional caveat, and the way to its site. */
@Composable
private fun RomCard(rom: DeGoogledRom, onOpenWebsite: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = rom.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Attribute(R.string.rom_label_google_free, rom.googleFreeByDefault)
        Attribute(R.string.rom_label_compat, rom.googleCompatibility)
        Attribute(R.string.rom_label_focus, rom.focus)
        Attribute(R.string.rom_label_hardware, rom.hardware)
        rom.note?.let { note ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        val openLabel = stringResource(R.string.rom_website_a11y, rom.name)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onOpenWebsite(rom.website) },
                // No side padding, so the action lines up with the labels above it.
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.semantics { contentDescription = openLabel }
            ) {
                Text(stringResource(R.string.rom_website))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = rom.website.displayHost(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Label and value read as one item by screen readers. */
@Composable
private fun Attribute(labelRes: Int, valueRes: Int) {
    Column(modifier = Modifier.padding(vertical = 2.dp).semantics(mergeDescendants = true) {}) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = stringResource(valueRes), style = MaterialTheme.typography.bodySmall)
    }
}

/** "https://www.shift.eco/shiftos/" shown as "shift.eco/shiftos". */
internal fun String.displayHost(): String =
    removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
