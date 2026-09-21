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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The import list (ADR-0023 in-app discovery): every contact that is
 * missing from Proton or whose Proton copy lacks details, with the
 * providers it comes from. Tapping a row runs the usual review dialog;
 * ticking rows and pressing Import brings them all in at once. Either
 * way the list rescans afterwards so rows update or disappear.
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
    val bulk by listViewModel.bulk.collectAsStateWithLifecycle()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val visible = (state as? LinkedImportListState.Ready)?.rows?.filteredBy(filter, query).orEmpty()

    LaunchedEffect(importState) {
        if (importState is LinkedImportState.Imported) listViewModel.rescan()
    }

    Scaffold(
        modifier = modifier,
        topBar = { ScreenTopBar(title = stringResource(R.string.linked_import_screen_title), onBack = onBack) },
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    onSelectAll = { listViewModel.selectAll(visible.map { it.contactId }) },
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
                    selected = selected,
                    onToggle = listViewModel::toggleSelected,
                    onOpen = importViewModel::start
                )
            }
        }
    }
    LinkedImportDialog(importViewModel)
    BulkImportDialog(state = bulk, onDismiss = listViewModel::dismissBulkResult)
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
    selected: Set<Long>,
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.contactId }) { row ->
            ContactRowItem(
                row = row,
                checked = row.contactId in selected,
                onToggle = { onToggle(row.contactId) },
                onClick = { onOpen(row.contactId) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ContactRowItem(row: LinkedContactRow, checked: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = row.name ?: stringResource(R.string.unverified_no_name),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = row.sources,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    }
}

/** Sticks to the bottom while something is ticked: select all / clear, and the one primary action. */
@Composable
private fun SelectionBar(count: Int, onSelectAll: () -> Unit, onClear: () -> Unit, onImport: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.linked_import_select_all)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.linked_import_clear_selection)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onImport) {
                Text(pluralStringResource(R.plurals.linked_import_bulk_button, count, count))
            }
        }
    }
}

@Composable
private fun BulkImportDialog(state: BulkImportState, onDismiss: () -> Unit) {
    when (state) {
        BulkImportState.Idle -> Unit
        is BulkImportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.linked_import_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.linked_import_bulk_progress, state.done, state.total))
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
        is BulkImportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.linked_import_dialog_title)) },
            text = {
                val r = state.result
                Text(stringResource(R.string.linked_import_bulk_done, r.created, r.updated, r.failed))
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.linked_import_close)) }
            }
        )
    }
}

private fun filterLabel(filter: LinkedImportFilter): Int = when (filter) {
    LinkedImportFilter.ALL -> R.string.linked_import_filter_all
    LinkedImportFilter.NOT_IN_PROTON -> R.string.linked_import_not_in_proton
    LinkedImportFilter.NEW_DETAILS -> R.string.linked_import_filter_new_details
}
