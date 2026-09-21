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

enum class LinkedFieldKind { PHONE, EMAIL, ADDRESS, ORGANIZATION, NOTE, IM }

/**
 * One importable detail. `id` is the position the app assigned when it
 * built the preview and is what [LinkedImportViewModel] hands back on
 * confirm; `value` and `source` are pre-formatted upstream (in `:app`).
 */
data class LinkedImportCandidate(
    val id: Int,
    val kind: LinkedFieldKind,
    val value: String,
    val source: String?
)

data class LinkedImportPreview(
    val contactName: String?,
    val candidates: List<LinkedImportCandidate>
)

sealed interface LinkedImportState {
    data object Hidden : LinkedImportState
    data object Loading : LinkedImportState

    /** The picked contact has no Proton copy to import into. */
    data object NotProtonContact : LinkedImportState
    data class Review(val preview: LinkedImportPreview, val selected: Set<Int>) : LinkedImportState
    data object Importing : LinkedImportState
    data class Imported(val count: Int) : LinkedImportState
    data class Failed(val reason: String) : LinkedImportState
}

/**
 * Drives the linked-contact import dialog (ADR-0023). Same shape as
 * [SettingsViewModel]: plain class, function-type seams, injectable
 * scope and dispatcher. `loadPreview` returns null when the contact has
 * no Proton copy; `importCandidates` receives the selected ids.
 */
class LinkedImportViewModel(
    private val loadPreview: suspend (Long) -> LinkedImportPreview?,
    private val importCandidates: suspend (List<Int>) -> Unit,
    private val scope: CoroutineScope = MainScope(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _state = MutableStateFlow<LinkedImportState>(LinkedImportState.Hidden)
    val state: StateFlow<LinkedImportState> = _state.asStateFlow()

    fun start(contactId: Long) {
        _state.value = LinkedImportState.Loading
        scope.launch {
            val preview = try {
                withContext(workDispatcher) { loadPreview(contactId) }
            } catch (e: Exception) {
                _state.value = LinkedImportState.Failed(e.javaClass.simpleName)
                return@launch
            }
            _state.value = if (preview == null) {
                LinkedImportState.NotProtonContact
            } else {
                LinkedImportState.Review(preview, preview.candidates.map { it.id }.toSet())
            }
        }
    }

    fun toggle(id: Int) {
        val review = _state.value as? LinkedImportState.Review ?: return
        val selected = if (id in review.selected) review.selected - id else review.selected + id
        _state.value = review.copy(selected = selected)
    }

    fun confirm() {
        val review = _state.value as? LinkedImportState.Review ?: return
        if (review.selected.isEmpty()) return
        _state.value = LinkedImportState.Importing
        scope.launch {
            _state.value = try {
                withContext(workDispatcher) { importCandidates(review.selected.sorted()) }
                LinkedImportState.Imported(review.selected.size)
            } catch (e: Exception) {
                LinkedImportState.Failed(e.javaClass.simpleName)
            }
        }
    }

    fun dismiss() {
        _state.value = LinkedImportState.Hidden
    }

    fun dispose() {
        scope.cancel()
    }
}
