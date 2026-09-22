// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Every artifact the release ships, with version, license and the CVEs the
 * build-time scan matched (ADR-0024). Problem artifacts come first. A CVE id
 * hands its NVD URL to [onOpenLink]; the host opens it with the platform's
 * external-link mechanism and returns false when nothing on the device can.
 */
@Composable
fun DependenciesScreen(
    audit: DependencyAudit,
    onOpenLink: (String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.rom_no_browser)
    val open: (String) -> Unit = { url ->
        if (!onOpenLink(url)) scope.launch { snackbarHostState.showSnackbar(noBrowser) }
    }
    Scaffold(
        modifier = modifier,
        topBar = { ScreenTopBar(title = stringResource(R.string.dependencies_screen_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            item { AuditSummary(audit) }
            items(audit.sortedForDisplay, key = { it.coordinate }) { dependency ->
                DependencyRow(dependency, open)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AuditSummary(audit: DependencyAudit) {
    Column {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(audit.status, size = 12.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(audit.status.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                color = audit.status.tint()
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dependencies_checked_on, audit.nvdDataAsOf ?: audit.generatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.dependencies_intro), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dependencies_count, audit.dependencies.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DependencyRow(dependency: AuditedDependency, open: (String) -> Unit) {
    val problem = dependency.status != AuditStatus.CLEAN
    val titleColor = if (problem) dependency.status.tint() else MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (problem) {
                StatusDot(dependency.status, size = 8.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = "${dependency.group}:${dependency.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (problem) FontWeight.SemiBold else FontWeight.Normal,
                color = titleColor
            )
        }
        Text(
            text = stringResource(
                R.string.dependencies_version_license,
                dependency.version,
                dependency.licenses.joinToString().ifEmpty { stringResource(R.string.dependencies_license_unknown) }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        dependency.cves.forEach { cve -> CveRow(cve, open) }
    }
}

@Composable
private fun CveRow(cve: AuditCve, open: (String) -> Unit) {
    val tone = if (cve.suppressed) AuditStatus.ASSESSED else AuditStatus.OPEN
    val score = listOfNotNull(cve.severity, cve.score?.toString()).joinToString(" ")
    Column(modifier = Modifier.padding(start = 14.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cve.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { open(cve.url) }
            )
            if (score.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(text = score, style = MaterialTheme.typography.bodySmall, color = tone.tint())
            }
        }
        Text(
            text = if (cve.suppressed) {
                stringResource(R.string.dependencies_cve_assessed, cve.reason.orEmpty())
            } else {
                stringResource(R.string.dependencies_cve_open)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (cve.suppressed) MaterialTheme.colorScheme.onSurfaceVariant else tone.tint()
        )
    }
}

/** The coloured circle used next to the version and on the list. */
@Composable
internal fun StatusDot(status: AuditStatus, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Spacer(modifier.size(size).clip(CircleShape).background(status.tint()))
}
