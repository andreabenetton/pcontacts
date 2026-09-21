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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * a completed import rescans so the row updates or disappears.
 */
@Composable
fun LinkedImportScreen(
    listViewModel: LinkedImportListViewModel,
    importViewModel: LinkedImportViewModel,
    modifier: Modifier = Modifier
) {
    val state by listViewModel.state.collectAsStateWithLifecycle()
    val filter by listViewModel.filter.collectAsStateWithLifecycle()
    val importState by importViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(importState) {
        if (importState is LinkedImportState.Imported) listViewModel.rescan()
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.linked_import_button),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        FilterRow(selected = filter, onSelect = listViewModel::setFilter)
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            LinkedImportListState.Scanning -> ScanningIndicator()
            is LinkedImportListState.Failed -> Text(
                text = stringResource(R.string.linked_import_scan_failed, s.reason),
                color = MaterialTheme.colorScheme.error
            )
            is LinkedImportListState.Ready -> ContactList(
                rows = s.rows.filteredBy(filter),
                onOpen = importViewModel::start
            )
        }
    }
    LinkedImportDialog(importViewModel)
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
private fun ScanningIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
        CircularProgressIndicator()
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text(stringResource(R.string.linked_import_scanning))
    }
}

@Composable
private fun ContactList(rows: List<LinkedContactRow>, onOpen: (Long) -> Unit) {
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
            ContactRowItem(row = row, onClick = { onOpen(row.contactId) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ContactRowItem(row: LinkedContactRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
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

private fun filterLabel(filter: LinkedImportFilter): Int = when (filter) {
    LinkedImportFilter.ALL -> R.string.linked_import_filter_all
    LinkedImportFilter.NOT_IN_PROTON -> R.string.linked_import_not_in_proton
    LinkedImportFilter.NEW_DETAILS -> R.string.linked_import_filter_new_details
}
