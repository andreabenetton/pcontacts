// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One contact in the import list. `sources` is the pre-formatted list
 * of providers it comes from ("WhatsApp, Signal"); `inProton` false
 * means tapping it creates the Proton copy, true means it adds the
 * `newFields` missing details.
 */
data class LinkedContactRow(
    val contactId: Long,
    val name: String?,
    val sources: String,
    val inProton: Boolean,
    val newFields: Int
)

enum class LinkedImportFilter { ALL, NOT_IN_PROTON, NEW_DETAILS }

sealed interface LinkedImportListState {
    data object Scanning : LinkedImportListState
    data class Ready(val rows: List<LinkedContactRow>) : LinkedImportListState
    data class Failed(val reason: String) : LinkedImportListState
}

/** Scans on creation and on [rescan]; the scan is the host's seam so this stays pure-JVM testable. */
class LinkedImportListViewModel(
    private val scan: suspend () -> List<LinkedContactRow>,
    private val scope: CoroutineScope = MainScope(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _state = MutableStateFlow<LinkedImportListState>(LinkedImportListState.Scanning)
    val state: StateFlow<LinkedImportListState> = _state.asStateFlow()

    private val _filter = MutableStateFlow(LinkedImportFilter.ALL)
    val filter: StateFlow<LinkedImportFilter> = _filter.asStateFlow()

    init {
        rescan()
    }

    fun rescan() {
        _state.value = LinkedImportListState.Scanning
        scope.launch {
            _state.value = try {
                LinkedImportListState.Ready(withContext(workDispatcher) { scan() })
            } catch (e: Exception) {
                LinkedImportListState.Failed(e.javaClass.simpleName)
            }
        }
    }

    fun setFilter(filter: LinkedImportFilter) {
        _filter.value = filter
    }

    fun dispose() {
        scope.cancel()
    }
}

fun List<LinkedContactRow>.filteredBy(filter: LinkedImportFilter): List<LinkedContactRow> = when (filter) {
    LinkedImportFilter.ALL -> this
    LinkedImportFilter.NOT_IN_PROTON -> filter { !it.inProton }
    LinkedImportFilter.NEW_DETAILS -> filter { it.inProton }
}
