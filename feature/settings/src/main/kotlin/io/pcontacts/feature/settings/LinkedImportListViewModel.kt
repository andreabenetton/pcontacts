// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
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
    val newFields: Int,
    /** The providers' launcher icons, same order as [sources]; empty for device-only rows. */
    val sourceIcons: List<Bitmap> = emptyList()
)

enum class LinkedImportFilter { ALL, NOT_IN_PROTON, NEW_DETAILS }

sealed interface LinkedImportListState {
    data object Scanning : LinkedImportListState
    data class Ready(val rows: List<LinkedContactRow>) : LinkedImportListState
    data class Failed(val reason: String) : LinkedImportListState
}

/** Outcome of importing a selection: contacts created in Proton, Proton copies given new details, failures. */
data class BulkResult(val created: Int, val enriched: Int, val failed: Int)

sealed interface BulkImportState {
    data object Idle : BulkImportState
    data class Running(val done: Int, val total: Int) : BulkImportState
    data class Done(val result: BulkResult) : BulkImportState
}

/**
 * Scans on creation and on [rescan]; keeps the filter, the search
 * query and the selection; runs a bulk import of the selection through
 * the host's `importMany` seam, which reports progress per contact.
 * Pure-JVM testable.
 */
/** Imports the given contacts whole, reporting how many are done so far; returns the tally. */
typealias BulkImporter = suspend (List<Long>, (Int) -> Unit) -> BulkResult

/** Where an imported contact's change to Proton stands, read from the outbox and the mapping. */
enum class ImportStatus { QUEUED, SYNCING, SYNCED, FAILED }

class LinkedImportListViewModel(
    private val scan: suspend () -> List<LinkedContactRow>,
    private val importMany: BulkImporter = { ids, _ -> BulkResult(0, 0, ids.size) },
    /** The contact's real status after a sync run; null when nothing is known yet (it stays queued). */
    private val queryImportStatus: suspend (contactId: Long) -> ImportStatus? = { null },
    private val scope: CoroutineScope = MainScope(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _state = MutableStateFlow<LinkedImportListState>(LinkedImportListState.Scanning)
    val state: StateFlow<LinkedImportListState> = _state.asStateFlow()

    private val _filter = MutableStateFlow(LinkedImportFilter.ALL)
    val filter: StateFlow<LinkedImportFilter> = _filter.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _bulk = MutableStateFlow<BulkImportState>(BulkImportState.Idle)
    val bulk: StateFlow<BulkImportState> = _bulk.asStateFlow()

    /**
     * Contacts imported one by one since the last scan, with where their
     * change stands; their rows say so instead of vanishing. "Added to
     * Proton" is claimed only once the outbox and the mapping say the
     * server accepted it — a sync run finishing proves nothing by itself.
     */
    private val _statuses = MutableStateFlow<Map<Long, ImportStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, ImportStatus>> = _statuses.asStateFlow()

    init {
        rescan()
    }

    fun rescan() {
        _state.value = LinkedImportListState.Scanning
        _statuses.value = emptyMap()
        scope.launch {
            _state.value = try {
                LinkedImportListState.Ready(withContext(workDispatcher) { scan() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LinkedImportListState.Failed(e.javaClass.simpleName)
            }
        }
    }

    fun setFilter(filter: LinkedImportFilter) {
        _filter.value = filter
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun markImported(contactId: Long) {
        _statuses.value = _statuses.value + (contactId to ImportStatus.QUEUED)
        _selected.value = _selected.value - contactId
    }

    /**
     * Fed by the host's sync-status observer. While a run is in flight
     * the queued rows say so; once it ends each of them is asked what
     * actually happened (queued again, synced, or failed).
     */
    fun updateSyncRunning(running: Boolean) {
        val open = _statuses.value.filterValues { it == ImportStatus.QUEUED || it == ImportStatus.SYNCING }.keys
        if (open.isEmpty()) return
        if (running) {
            _statuses.value = _statuses.value + open.associateWith { ImportStatus.SYNCING }
            return
        }
        scope.launch {
            val resolved = withContext(workDispatcher) {
                open.associateWith { id -> queryImportStatus(id) ?: ImportStatus.QUEUED }
            }
            _statuses.value = _statuses.value + resolved
        }
    }

    fun toggleSelected(contactId: Long) {
        _selected.value = if (contactId in _selected.value) _selected.value - contactId else _selected.value + contactId
    }

    fun selectAll(contactIds: Collection<Long>) {
        _selected.value = _selected.value + contactIds
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Imports every selected contact; the list rescans afterwards so imported rows update or vanish. */
    fun importSelected() {
        val ids = _selected.value.toList()
        if (ids.isEmpty() || _bulk.value is BulkImportState.Running) return
        _bulk.value = BulkImportState.Running(0, ids.size)
        scope.launch {
            val result = try {
                withContext(workDispatcher) {
                    importMany(ids) { done -> _bulk.value = BulkImportState.Running(done, ids.size) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BulkResult(created = 0, enriched = 0, failed = ids.size)
            }
            _bulk.value = BulkImportState.Done(result)
            _selected.value = emptySet()
            rescan()
        }
    }

    fun dismissBulkResult() {
        _bulk.value = BulkImportState.Idle
    }

    fun dispose() {
        scope.cancel()
    }
}

/** The rows the filter chip admits, narrowed to those whose name or providers contain [query]. */
fun List<LinkedContactRow>.filteredBy(filter: LinkedImportFilter, query: String = ""): List<LinkedContactRow> {
    val byFilter = when (filter) {
        LinkedImportFilter.ALL -> this
        LinkedImportFilter.NOT_IN_PROTON -> filter { !it.inProton }
        LinkedImportFilter.NEW_DETAILS -> filter { it.inProton }
    }
    val needle = query.trim()
    if (needle.isEmpty()) return byFilter
    return byFilter.filter { row ->
        row.name?.contains(needle, ignoreCase = true) == true || row.sources.contains(needle, ignoreCase = true)
    }
}
