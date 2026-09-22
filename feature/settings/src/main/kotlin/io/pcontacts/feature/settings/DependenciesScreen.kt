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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
// One parameter per fact the host knows and the screen shows; there is no state to bundle them in.
@Suppress("LongParameterList")
@Composable
fun DependenciesScreen(
    audit: DependencyAudit,
    onOpenLink: (String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The opt-in runtime check (ADR-0025); its findings are merged into the list. */
    runtime: AdvisoryCheckState = AdvisoryCheckState.OFF,
    checking: Boolean = false,
    onCheckNow: () -> Unit = {},
    /** Mute or unmute a runtime advisory: the user's own assessment (amber when muted). */
    onMute: (RuntimeAdvisory, Boolean) -> Unit = { _, _ -> }
) {
    val merged = audit.withRuntime(runtime)
    val runtimeByKey = runtime.advisories.associateBy { it.key }
    // Colours and the mute control belong to the runtime check; with it off the screen is a plain list.
    val coloured = runtime.enabled
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
            item { AuditSummary(merged, runtime, checking, onCheckNow) }
            items(merged.sortedForDisplay, key = { it.coordinate }) { dependency ->
                DependencyRow(dependency, open, coloured) { cve, muted ->
                    runtimeByKey["${dependency.coordinate}|${cve.id}"]?.let { onMute(it, muted) }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AuditSummary(
    audit: DependencyAudit,
    runtime: AdvisoryCheckState,
    checking: Boolean,
    onCheckNow: () -> Unit
) {
    Column {
        Spacer(Modifier.height(12.dp))
        if (runtime.enabled) {
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
        }
        Text(
            text = stringResource(R.string.dependencies_checked_on, audit.nvdDataAsOf ?: audit.generatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (runtime.enabled) R.string.dependencies_intro_runtime else R.string.dependencies_intro_snapshot
            ),
            style = MaterialTheme.typography.bodySmall
        )
        if (runtime.enabled) {
            Spacer(Modifier.height(4.dp))
            AdvisoryCheckStatusLine(runtime, checking)
            TextButton(onClick = onCheckNow, enabled = !checking) {
                Text(stringResource(R.string.advisory_check_now))
            }
        }
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
private fun DependencyRow(
    dependency: AuditedDependency,
    open: (String) -> Unit,
    coloured: Boolean,
    mute: (AuditCve, Boolean) -> Unit
) {
    val problem = coloured && dependency.status != AuditStatus.CLEAN
    val titleColor = if (problem) dependency.status.tint() else MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
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
            text = stringResource(R.string.dependencies_version, dependency.version),
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
        Text(
            text = dependency.licenses.joinToString().ifEmpty { stringResource(R.string.dependencies_license_unknown) },
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
        // Open entries and runtime advisories (muted or not) are listed one by one.
        dependency.cves.filter { !it.suppressed || it.runtime }.forEach { cve ->
            CveRow(cve, open, coloured, mute = if (cve.runtime && coloured) ({ muted -> mute(cve, muted) }) else null)
        }
        // Suppressed matches share a reason per artifact (a CPE false positive lists dozens);
        // one line with the count and the reason, the ids on demand. A false positive is not
        // a CVE of this artifact and is shown in grey; an assessed real one in amber.
        dependency.cves.filter { it.suppressed && !it.runtime }.groupBy { it.reason.orEmpty() }.forEach { (reason, cves) ->
            AssessedGroup(reason, cves, open, coloured)
        }
    }
}

@Composable
private fun AssessedGroup(reason: String, cves: List<AuditCve>, open: (String) -> Unit, coloured: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val falsePositive = cves.all { it.falsePositive }
    val label = stringResource(
        if (falsePositive) R.string.dependencies_false_positive_group else R.string.dependencies_assessed_group,
        cves.size
    )
    val tint = if (falsePositive || !coloured) MaterialTheme.colorScheme.onSurfaceVariant else AuditStatus.ASSESSED.tint()
    Column(modifier = Modifier.padding(start = 14.dp, top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics { contentDescription = label }
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (expanded) cves.forEach { cve -> CveRow(cve, open, coloured) }
    }
}

/** One CVE: the id as a link, its score, its state, and for a runtime advisory the mute control. */
@Composable
private fun CveRow(cve: AuditCve, open: (String) -> Unit, coloured: Boolean, mute: ((Boolean) -> Unit)? = null) {
    val tone = if (cve.suppressed) AuditStatus.ASSESSED else AuditStatus.OPEN
    val plain = MaterialTheme.colorScheme.onSurfaceVariant
    val toneColor = if (coloured) tone.tint() else plain
    val scoreColor = if (cve.falsePositive) plain else toneColor
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
                Text(text = score, style = MaterialTheme.typography.bodySmall, color = scoreColor)
            }
        }
        if (!cve.suppressed || cve.runtime) {
            Text(
                text = stringResource(
                    when {
                        cve.runtime && cve.suppressed -> R.string.dependencies_cve_muted
                        cve.runtime -> R.string.dependencies_cve_runtime
                        else -> R.string.dependencies_cve_open
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = toneColor
            )
            cve.reason?.takeIf { cve.runtime }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            mute?.let { toggle ->
                TextButton(onClick = { toggle(!cve.suppressed) }) {
                    Text(
                        stringResource(if (cve.suppressed) R.string.dependencies_unmute else R.string.dependencies_mute)
                    )
                }
            }
        }
    }
}

/** The coloured circle used next to the version and on the list. */
@Composable
internal fun StatusDot(status: AuditStatus, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Spacer(modifier.size(size).clip(CircleShape).background(status.tint()))
}
