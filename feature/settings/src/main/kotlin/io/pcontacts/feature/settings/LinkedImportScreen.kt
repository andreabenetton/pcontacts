// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * The import list (ADR-0023 in-app discovery): every contact that is
 * missing from Proton or whose Proton copy lacks details, with the
 * providers it comes from. Tapping a row runs the review dialog and the
 * row then says "Added to Proton"; ticking rows and pressing Import
 * brings them all in at once and the list rescans. Outcomes are
 * snackbars; dialogs are only for decisions and progress.
 */
@Composable
fun LinkedImportScreen(
    listViewModel: LinkedImportListViewModel,
    importViewModel: LinkedImportViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by listViewModel.state.collectAsStateWithLifecycle()
    val filter by listViewModel.filter.collectAsStateWithLifecycle()
    val query by listViewModel.query.collectAsStateWithLifecycle()
    val selected by listViewModel.selected.collectAsStateWithLifecycle()
    val statuses by listViewModel.statuses.collectAsStateWithLifecycle()
    val imported = statuses.keys
    val bulk by listViewModel.bulk.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted so the scroll position outlives the rescan that follows every import.
    val listState = rememberLazyListState()
    val visible = (state as? LinkedImportListState.Ready)?.rows?.filteredBy(filter, query).orEmpty()

    ImportOutcomes(importViewModel, listViewModel, snackbarHostState)

    Scaffold(
        modifier = modifier,
        topBar = { ScreenTopBar(title = stringResource(R.string.linked_import_screen_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    moves = visible.count { it.contactId in selected && it.move == RowMove.MOVES },
                    onSelectAll = { listViewModel.selectAll(visible.map { it.contactId } - imported) },
                    onClear = listViewModel::clearSelection,
                    onImport = listViewModel::importSelected
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            FilterRow(selected = filter, onSelect = listViewModel::setFilter)
            Spacer(Modifier.height(8.dp))
            SearchField(query = query, onChange = listViewModel::setQuery)
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                LinkedImportListState.Scanning -> ScanningIndicator()
                is LinkedImportListState.Failed -> Text(
                    text = stringResource(R.string.linked_import_scan_failed, s.reason),
                    color = MaterialTheme.colorScheme.error
                )
                is LinkedImportListState.Ready -> ContactList(
                    rows = visible,
                    listState = listState,
                    selected = selected,
                    statuses = statuses,
                    onToggle = listViewModel::toggleSelected,
                    onOpen = importViewModel::start
                )
            }
        }
    }
    LinkedImportDialog(importViewModel)
    if (bulk is BulkImportState.Running) BulkProgressDialog(bulk as BulkImportState.Running)
}

/**
 * Turns the two view models' outcomes into snackbars and row state:
 * a single import marks its row, a bulk import reports its tally and
 * the list rescans (already triggered by the view model).
 */
@Composable
private fun ImportOutcomes(
    importViewModel: LinkedImportViewModel,
    listViewModel: LinkedImportListViewModel,
    snackbarHostState: SnackbarHostState
) {
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val bulk by listViewModel.bulk.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    // The effects reset the state they are keyed on, which restarts them: a snackbar shown from
    // their own coroutine was cancelled at once. It runs in the screen's scope instead.
    val snackbarScope = rememberCoroutineScope()

    LaunchedEffect(importState) {
        val message = when (val s = importState) {
            is LinkedImportState.Imported -> {
                listViewModel.markImported(s.contactId)
                val plural = if (s.created) R.plurals.linked_import_created else R.plurals.linked_import_done
                resources.getQuantityString(plural, s.count, s.count)
            }
            is LinkedImportState.Failed -> resources.getString(R.string.linked_import_failed, s.reason)
            LinkedImportState.NotFound -> resources.getString(R.string.linked_import_not_found)
            else -> null
        }
        if (message != null) {
            importViewModel.dismiss()
            snackbarScope.launch { snackbarHostState.showSnackbar(message) }
        }
    }
    LaunchedEffect(bulk) {
        val done = bulk as? BulkImportState.Done ?: return@LaunchedEffect
        val r = done.result
        listViewModel.dismissBulkResult()
        val message = if (r.moved > 0) {
            resources.getString(R.string.linked_import_bulk_done_moves, r.moved, r.created, r.enriched, r.failed)
        } else {
            resources.getString(R.string.linked_import_bulk_done, r.created, r.enriched, r.failed)
        }
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }
}

@Composable
private fun FilterRow(selected: LinkedImportFilter, onSelect: (LinkedImportFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkedImportFilter.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(stringResource(filterLabel(option))) }
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.linked_import_search_hint)) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ScanningIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
        CircularProgressIndicator()
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text(stringResource(R.string.linked_import_scanning))
    }
}

@Composable
private fun ContactList(
    rows: List<LinkedContactRow>,
    listState: LazyListState,
    selected: Set<Long>,
    statuses: Map<Long, ImportStatus>,
    onToggle: (Long) -> Unit,
    onOpen: (Long) -> Unit
) {
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.linked_import_list_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.contactId }) { row ->
            val status = statuses[row.contactId]
            ContactRowItem(
                row = row,
                checked = row.contactId in selected,
                status = status,
                onToggle = { onToggle(row.contactId) },
                onClick = { if (status == null) onOpen(row.contactId) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ContactRowItem(
    row: LinkedContactRow,
    checked: Boolean,
    status: ImportStatus?,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val done = status != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        val name = row.name ?: stringResource(R.string.unverified_no_name)
        // The checkbox is its own accessibility node; naming it keeps "Select Alice" apart from the row.
        val selectLabel = stringResource(R.string.linked_import_select_a11y, name)
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = !done,
            modifier = Modifier.semantics { contentDescription = selectLabel }
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceIcons(row.sourceIcons)
                Text(
                    text = row.sources,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RowBadge(row, status)
        }
    }
}

/**
 * "Not in Proton" / "N new details" before an import; afterwards where
 * the change stands: waiting, syncing, added (once the server accepted
 * it) or failed (a quarantined change, listed in Settings).
 */
@Composable
private fun RowBadge(row: LinkedContactRow, status: ImportStatus?) {
    if (status != null) {
        val (tone, textRes) = when (status) {
            ImportStatus.QUEUED -> SyncTone.INFO to R.string.linked_import_row_queued
            ImportStatus.SYNCING -> SyncTone.RUNNING to R.string.linked_import_row_syncing
            ImportStatus.SYNCED -> SyncTone.OK to R.string.linked_import_row_added
            ImportStatus.FAILED -> SyncTone.WARN to R.string.linked_import_row_failed
        }
        SyncIndicator(tone = tone, text = stringResource(textRes))
        return
    }
    // ADR-0026: say before any tick that Android deletes this storage and what the import does.
    val moveRes = when (row.move) {
        RowMove.MOVES -> R.string.linked_import_row_moves
        RowMove.NEEDS_REVIEW -> R.string.linked_import_row_move_review
        RowMove.NONE -> null
    }
    if (moveRes != null) {
        SyncIndicator(tone = SyncTone.WARN, text = stringResource(moveRes))
        return
    }
    Text(
        text = if (row.inProton) {
            pluralStringResource(R.plurals.linked_import_new_details, row.newFields, row.newFields)
        } else {
            stringResource(R.string.linked_import_not_in_proton)
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Sticks to the bottom while something is ticked: select all / clear, and the one primary action. */
@Composable
private fun SelectionBar(
    count: Int,
    moves: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onImport: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.linked_import_select_all)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.linked_import_clear_selection)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onImport) {
                Text(
                    if (moves > 0) {
                        pluralStringResource(R.plurals.linked_import_bulk_button_moves, count, count, moves)
                    } else {
                        pluralStringResource(R.plurals.linked_import_bulk_button, count, count)
                    }
                )
            }
        }
    }
}

@Composable
private fun BulkProgressDialog(state: BulkImportState.Running) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.linked_import_dialog_title)) },
        text = {
            Column {
                SyncIndicator(
                    tone = SyncTone.RUNNING,
                    text = stringResource(R.string.linked_import_bulk_progress, state.done, state.total),
                    style = MaterialTheme.typography.bodyMedium,
                    glyphSize = 20.dp
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {}
    )
}

private fun filterLabel(filter: LinkedImportFilter): Int = when (filter) {
    LinkedImportFilter.ALL -> R.string.linked_import_filter_all
    LinkedImportFilter.NOT_IN_PROTON -> R.string.linked_import_not_in_proton
    LinkedImportFilter.NEW_DETAILS -> R.string.linked_import_filter_new_details
}
