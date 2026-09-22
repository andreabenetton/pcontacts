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

enum class LinkedFieldKind { PHONE, EMAIL, ADDRESS, ORGANIZATION, NOTE, IM }

/** Kinds a contact can be reached by; a new Proton contact needs at least one of them. */
private val CONTACT_KINDS = setOf(
    LinkedFieldKind.PHONE,
    LinkedFieldKind.EMAIL,
    LinkedFieldKind.ADDRESS,
    LinkedFieldKind.IM
)

/**
 * One importable detail. `id` is the field's stable identity (kind and
 * normalised value, assigned in `:app` from `LinkedField.key`) and is
 * what [LinkedImportViewModel] hands back on confirm — the candidates
 * are re-read at that moment, so a position would point at the wrong
 * field after a re-aggregation; `value` and `source` are pre-formatted
 * upstream.
 */
data class LinkedImportCandidate(
    val id: String,
    val kind: LinkedFieldKind,
    val value: String,
    val source: String?,
    val sourceIcons: List<Bitmap> = emptyList()
)

/** `createsNewContact` is true when the contact has no Proton copy and confirming creates one. */
data class LinkedImportPreview(
    val contactName: String?,
    val candidates: List<LinkedImportCandidate>,
    val createsNewContact: Boolean = false
)

sealed interface LinkedImportState {
    data object Hidden : LinkedImportState
    data object Loading : LinkedImportState

    /** The picked contact no longer exists. */
    data object NotFound : LinkedImportState

    /** [changed]: the contact was re-aggregated between preview and confirm; the list is fresh, review again. */
    data class Review(
        val preview: LinkedImportPreview,
        val selected: Set<String>,
        val changed: Boolean = false
    ) : LinkedImportState {
        /** A selection that reaches the contact somehow; a note-only new contact is not one. */
        val canConfirm: Boolean
            get() {
                if (selected.isEmpty()) return false
                if (!preview.createsNewContact) return true
                return preview.candidates.any { it.id in selected && it.kind in CONTACT_KINDS }
            }
    }

    data object Importing : LinkedImportState
    data class Imported(val count: Int, val created: Boolean = false, val contactId: Long = 0L) : LinkedImportState
    data class Failed(val reason: String) : LinkedImportState
}

/**
 * Drives the linked-contact import dialog (ADR-0023). Same shape as
 * [SettingsViewModel]: plain class, function-type seams, injectable
 * scope and dispatcher. `loadPreview` returns null when the contact no
 * longer exists; `importCandidates` receives the selected field keys
 * and, for a preview that creates a new contact, creates it. It
 * answers false when the contact changed meanwhile and a selected
 * field is gone: the preview is reloaded for another look.
 */
class LinkedImportViewModel(
    private val loadPreview: suspend (Long) -> LinkedImportPreview?,
    private val importCandidates: suspend (List<String>) -> Boolean,
    private val scope: CoroutineScope = MainScope(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _state = MutableStateFlow<LinkedImportState>(LinkedImportState.Hidden)
    val state: StateFlow<LinkedImportState> = _state.asStateFlow()

    private var contactId: Long = 0L

    fun start(contactId: Long) {
        this.contactId = contactId
        _state.value = LinkedImportState.Loading
        scope.launch { _state.value = load(changed = false) }
    }

    private suspend fun load(changed: Boolean): LinkedImportState {
        val preview = try {
            withContext(workDispatcher) { loadPreview(contactId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LinkedImportState.Failed(e.javaClass.simpleName)
        }
        return if (preview == null) {
            LinkedImportState.NotFound
        } else {
            LinkedImportState.Review(preview, preview.candidates.map { it.id }.toSet(), changed)
        }
    }

    fun toggle(id: String) {
        val review = _state.value as? LinkedImportState.Review ?: return
        val selected = if (id in review.selected) review.selected - id else review.selected + id
        _state.value = review.copy(selected = selected)
    }

    fun confirm() {
        val review = _state.value as? LinkedImportState.Review ?: return
        if (!review.canConfirm) return
        _state.value = LinkedImportState.Importing
        scope.launch {
            _state.value = try {
                val done = withContext(workDispatcher) { importCandidates(review.selected.sorted()) }
                if (done) {
                    LinkedImportState.Imported(review.selected.size, review.preview.createsNewContact, contactId)
                } else {
                    load(changed = true)
                }
            } catch (e: CancellationException) {
                throw e
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
