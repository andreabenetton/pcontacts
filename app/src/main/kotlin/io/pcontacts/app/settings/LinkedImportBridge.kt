// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.graphics.drawable.toBitmap
import io.pcontacts.app.R
import io.pcontacts.core.contactswriter.LinkedContactCandidates
import io.pcontacts.core.contactswriter.LinkedField
import io.pcontacts.core.sync.contacts.LinkedContactsBootstrap
import io.pcontacts.feature.settings.BulkResult
import io.pcontacts.feature.settings.LinkedContactRow
import io.pcontacts.feature.settings.LinkedFieldKind
import io.pcontacts.feature.settings.LinkedImportCandidate
import io.pcontacts.feature.settings.LinkedImportPreview

/**
 * `:app` side of the linked-contact import (ADR-0023): resolves the
 * picked Contact, turns the core candidates into display rows for the
 * dialog, and replays the user's selection through
 * [LinkedContactsBootstrap]. The candidate list lives here only between
 * preview and import — never persisted, never logged.
 */
class LinkedImportBridge(
    private val context: Context,
    private val account: () -> Account?
) {
    private var loaded: LinkedContactCandidates? = null
    private var loadedContactId: Long = 0L

    /** Launcher icons per provider account type, loaded once per bridge; null where there is no app (device-local). */
    private val iconCache = HashMap<String?, Bitmap?>()

    /** Every contact with something to bring into Proton, providers resolved to labels. */
    suspend fun scan(): List<LinkedContactRow> {
        val account = account() ?: return emptyList()
        return LinkedContactsBootstrap.scanLinkedContacts(context, account).map { summary ->
            LinkedContactRow(
                contactId = summary.contactId,
                name = summary.displayName,
                sources = summary.sourceAccountTypes.map(::sourceLabel).distinct().joinToString(", "),
                sourceIcons = sourceIcons(summary.sourceAccountTypes),
                inProton = summary.hasProtonCopy,
                newFields = summary.newFieldCount
            )
        }
    }

    /**
     * Imports whole contacts without review: every candidate the scan
     * found, created or appended as for the single flow. One sync
     * request at the end. A contact that vanished or whose write fails
     * counts as failed and does not stop the rest.
     */
    suspend fun importMany(contactIds: List<Long>, onProgress: (Int) -> Unit): BulkResult {
        val account = account() ?: return BulkResult(0, 0, contactIds.size)
        var created = 0
        var enriched = 0
        var failed = 0
        contactIds.forEachIndexed { index, contactId ->
            when (importWhole(account, contactId)) {
                WholeImport.CREATED -> created++
                WholeImport.ENRICHED -> enriched++
                WholeImport.FAILED -> failed++
            }
            onProgress(index + 1)
        }
        requestSync(account)
        return BulkResult(created, enriched, failed)
    }

    private enum class WholeImport { CREATED, ENRICHED, FAILED }

    private suspend fun importWhole(account: Account, contactId: Long): WholeImport {
        val candidates = LinkedContactsBootstrap.loadCandidates(context, account, contactId) ?: return WholeImport.FAILED
        val fields = candidates.candidates.map { it.field }
        if (fields.isEmpty()) return WholeImport.FAILED
        val protonRawContactId = candidates.protonRawContactId
        return try {
            if (protonRawContactId == null) {
                LinkedContactsBootstrap.createContact(context, account, contactId, candidates.name, fields)
                WholeImport.CREATED
            } else {
                LinkedContactsBootstrap.importFields(context, account, protonRawContactId, fields)
                WholeImport.ENRICHED
            }
        } catch (_: IllegalArgumentException) {
            // ContactRow's guard: nothing reachable to create a contact from.
            WholeImport.FAILED
        } catch (_: android.os.RemoteException) {
            WholeImport.FAILED
        }
    }

    suspend fun loadPreview(contactId: Long): LinkedImportPreview? {
        val account = account() ?: return null
        val candidates = LinkedContactsBootstrap.loadCandidates(context, account, contactId) ?: return null
        loaded = candidates
        loadedContactId = contactId
        return LinkedImportPreview(
            contactName = contactName(contactId),
            createsNewContact = candidates.protonRawContactId == null,
            candidates = candidates.candidates.mapIndexed { idx, candidate ->
                LinkedImportCandidate(
                    id = idx,
                    kind = LinkedImportFormat.kind(candidate.field),
                    value = LinkedImportFormat.value(candidate.field),
                    source = candidate.sourceAccountTypes.map(::sourceLabel).distinct().joinToString(", "),
                    sourceIcons = sourceIcons(candidate.sourceAccountTypes)
                )
            }
        )
    }

    /**
     * Writes the chosen fields onto the Proton copy — or creates the
     * copy when there is none — and asks for a sync so they reach
     * Proton promptly.
     */
    suspend fun import(ids: List<Int>) {
        val account = account() ?: return
        val candidates = loaded ?: return
        val fields = ids.map { candidates.candidates[it].field }
        val protonRawContactId = candidates.protonRawContactId
        if (protonRawContactId == null) {
            LinkedContactsBootstrap.createContact(context, account, loadedContactId, candidates.name, fields)
        } else {
            LinkedContactsBootstrap.importFields(context, account, protonRawContactId, fields)
        }
        loaded = null
        requestSync(account)
    }

    private fun requestSync(account: Account) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
    }

    private fun contactName(contactId: Long): String? =
        context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** The providers' launcher icons, in the given order, skipping device-local rows. */
    private fun sourceIcons(accountTypes: List<String?>): List<Bitmap> =
        accountTypes.distinct().mapNotNull { type ->
            iconCache.getOrPut(type) {
                authenticatorOf(type)?.let { auth ->
                    try {
                        context.packageManager.getApplicationIcon(auth.packageName).toBitmap(ICON_PX, ICON_PX)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
            }
        }

    private fun authenticatorOf(accountType: String?): AuthenticatorDescription? =
        accountType?.let { type -> AccountManager.get(context).authenticatorTypes.firstOrNull { it.type == type } }

    /**
     * The owning app's authenticator label ("WhatsApp"). A type no
     * authenticator claims (null, or a bare "PHONE" as seen on Pixel) is
     * device-local.
     */
    private fun sourceLabel(accountType: String?): String {
        val device = context.getString(R.string.linked_import_source_device)
        if (accountType == null) return device
        val authenticator = authenticatorOf(accountType) ?: return device
        return try {
            context.packageManager
                .getResourcesForApplication(authenticator.packageName)
                .getString(authenticator.labelId)
        } catch (_: PackageManager.NameNotFoundException) {
            accountType
        } catch (_: Resources.NotFoundException) {
            accountType
        }
    }
}

/** Pure display mapping of a [LinkedField]; kept separate so it is testable without Android. */
internal object LinkedImportFormat {

    fun kind(field: LinkedField): LinkedFieldKind = when (field) {
        is LinkedField.PhoneNumber -> LinkedFieldKind.PHONE
        is LinkedField.EmailAddress -> LinkedFieldKind.EMAIL
        is LinkedField.Address -> LinkedFieldKind.ADDRESS
        is LinkedField.Org -> LinkedFieldKind.ORGANIZATION
        is LinkedField.NoteText -> LinkedFieldKind.NOTE
        is LinkedField.Im -> LinkedFieldKind.IM
    }

    fun value(field: LinkedField): String = when (field) {
        is LinkedField.PhoneNumber -> field.phone.number
        is LinkedField.EmailAddress -> field.address
        is LinkedField.Address -> with(field.address) {
            listOf(poBox, neighborhood, street, city, region, postcode, country)
                .filter { !it.isNullOrBlank() }
                .joinToString(", ")
        }
        is LinkedField.Org -> with(field.organization) {
            listOf(company, department, title).filter { !it.isNullOrBlank() }.joinToString(" · ")
        }
        is LinkedField.NoteText -> field.note
        is LinkedField.Im -> with(field.account) {
            "${customProtocol ?: protocol.name.lowercase()}: $handle"
        }
    }
}

/** Provider icons are shown at 16 dp; 96 px is plenty for any density. */
private const val ICON_PX = 96
