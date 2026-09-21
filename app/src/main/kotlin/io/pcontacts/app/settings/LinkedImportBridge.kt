// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.provider.ContactsContract
import io.pcontacts.app.R
import io.pcontacts.core.contactswriter.LinkedContactCandidates
import io.pcontacts.core.contactswriter.LinkedField
import io.pcontacts.core.sync.contacts.LinkedContactsBootstrap
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

    /** Every contact with something to bring into Proton, providers resolved to labels. */
    suspend fun scan(): List<LinkedContactRow> {
        val account = account() ?: return emptyList()
        return LinkedContactsBootstrap.scanLinkedContacts(context, account).map { summary ->
            LinkedContactRow(
                contactId = summary.contactId,
                name = summary.displayName,
                sources = summary.sourceAccountTypes.map(::sourceLabel).distinct().joinToString(", "),
                inProton = summary.hasProtonCopy,
                newFields = summary.newFieldCount
            )
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
                    source = candidate.sourceAccountTypes.map(::sourceLabel).distinct().joinToString(", ")
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

    /**
     * The owning app's authenticator label ("WhatsApp"). A type no
     * authenticator claims (null, or a bare "PHONE" as seen on Pixel) is
     * device-local.
     */
    private fun sourceLabel(accountType: String?): String {
        val device = context.getString(R.string.linked_import_source_device)
        if (accountType == null) return device
        val authenticator = AccountManager.get(context).authenticatorTypes
            .firstOrNull { it.type == accountType }
            ?: return device
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
