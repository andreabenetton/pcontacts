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
import io.pcontacts.core.contactswriter.LinkedField
import io.pcontacts.core.contactswriter.UncarriedKind
import io.pcontacts.core.contactswriter.key
import io.pcontacts.core.contactswriter.reachesContact
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.sync.contacts.LinkedContactsBootstrap
import io.pcontacts.feature.settings.BulkResult
import io.pcontacts.feature.settings.ImportStatus
import io.pcontacts.feature.settings.LinkedContactRow
import io.pcontacts.feature.settings.LinkedFieldKind
import io.pcontacts.feature.settings.LinkedImportCandidate
import io.pcontacts.feature.settings.LinkedImportPreview
import io.pcontacts.feature.settings.MoveOffer
import io.pcontacts.feature.settings.UncarriedDetail

/**
 * `:app` side of the linked-contact import (ADR-0023): resolves the
 * picked Contact, turns the core candidates into display rows for the
 * dialog, and replays the user's selection through
 * [LinkedContactsBootstrap]. Nothing is cached between preview and
 * import (ADR-0023): the candidates are re-read at confirmation and
 * the selection is matched by field identity, so a re-aggregation in
 * between cannot import the wrong field.
 */
class LinkedImportBridge(
    private val context: Context,
    private val account: () -> Account?
) {
    private var loadedContactId: Long = 0L
    private var loadedProtonRawContactId: Long? = null
    private var loadedMovableRawContactId: Long? = null

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
        // A new Proton contact is created only from something that reaches the person (ADR-0023).
        if (protonRawContactId == null && fields.none { it.reachesContact }) return WholeImport.FAILED
        return try {
            if (protonRawContactId == null) {
                LinkedContactsBootstrap.createContact(context, account, contactId, candidates.name, fields)
                WholeImport.CREATED
            } else {
                LinkedContactsBootstrap.importFields(context, account, protonRawContactId, fields)
                WholeImport.ENRICHED
            }
        } catch (_: android.os.RemoteException) {
            WholeImport.FAILED
        }
    }

    suspend fun loadPreview(contactId: Long): LinkedImportPreview? {
        val account = account() ?: return null
        val candidates = LinkedContactsBootstrap.loadCandidates(context, account, contactId) ?: return null
        loadedContactId = contactId
        loadedProtonRawContactId = candidates.protonRawContactId
        loadedMovableRawContactId = candidates.movableRawContactId
        return LinkedImportPreview(
            contactName = contactName(contactId),
            createsNewContact = candidates.protonRawContactId == null,
            move = candidates.movableRawContactId?.let {
                MoveOffer(candidates.uncarried.map(LinkedImportFormat::uncarried))
            },
            candidates = candidates.candidates.map { candidate ->
                LinkedImportCandidate(
                    id = candidate.field.key,
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
     * Proton promptly. The candidates are re-read now; false means the
     * contact changed since the preview (a chosen field is gone, or the
     * Proton copy is not the one previewed) and the user should look again.
     */
    suspend fun import(keys: List<String>): Boolean {
        val account = account() ?: return true
        val candidates = LinkedContactsBootstrap.loadCandidates(context, account, loadedContactId) ?: return false
        if (candidates.protonRawContactId != loadedProtonRawContactId) return false
        val byKey = candidates.candidates.associateBy { it.field.key }
        val fields = keys.map { key -> byKey[key]?.field ?: return false }
        val protonRawContactId = candidates.protonRawContactId
        if (protonRawContactId == null) {
            LinkedContactsBootstrap.createContact(context, account, loadedContactId, candidates.name, fields)
        } else {
            LinkedContactsBootstrap.importFields(context, account, protonRawContactId, fields)
        }
        requestSync(account)
        return true
    }

    /**
     * Moves the previewed orphan `PHONE` contact into the Proton account
     * (ADR-0026) and asks for a sync, which creates it on Proton. The
     * candidates are re-read first: false when the contact changed since
     * the preview — it gained a Proton copy or its orphan row is gone.
     */
    suspend fun move(): Boolean {
        val account = account() ?: return true
        val candidates = LinkedContactsBootstrap.loadCandidates(context, account, loadedContactId) ?: return false
        val rawContactId = candidates.movableRawContactId
        if (rawContactId == null || rawContactId != loadedMovableRawContactId) return false
        if (!LinkedContactsBootstrap.moveContact(context, account, rawContactId)) return false
        requestSync(account)
        return true
    }

    /**
     * Where the imported contact's change to Proton stands, from the
     * outbox and the mapping of its Proton RawContact: a live outbox row
     * is queued, a quarantined one failed, a clean mapping under a server
     * id is synced. Null when nothing is known yet.
     */
    suspend fun importStatus(contactId: Long): ImportStatus? {
        val account = account() ?: return null
        val rawId = LinkedContactsBootstrap.loadCandidates(context, account, contactId)?.protonRawContactId
            ?: return null
        val db = DatabaseFactory.create(context)
        val mapping = db.contactMapDao().findByRawContactId(rawId) ?: return null
        val rows = db.outboxDao().findByContact(mapping.protonContactId)
        return ImportStatusMapper.status(mapping, rows)
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
        is LinkedField.Birthday -> LinkedFieldKind.BIRTHDAY
        is LinkedField.Anniversary -> LinkedFieldKind.ANNIVERSARY
        is LinkedField.NicknameText -> LinkedFieldKind.NICKNAME
        is LinkedField.WebsiteUrl -> LinkedFieldKind.WEBSITE
    }

    fun uncarried(kind: UncarriedKind): UncarriedDetail = when (kind) {
        UncarriedKind.EVENT -> UncarriedDetail.EVENT
        UncarriedKind.RELATION -> UncarriedDetail.RELATION
        UncarriedKind.SIP_ADDRESS -> UncarriedDetail.SIP_ADDRESS
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
        // Dates as the phone stores them: yyyy-MM-dd, or --MM-dd without a year.
        is LinkedField.Birthday -> field.date
        is LinkedField.Anniversary -> field.date
        is LinkedField.NicknameText -> field.name
        is LinkedField.WebsiteUrl -> field.url
    }
}

/** Provider icons are shown at 16 dp; 96 px is plenty for any density. */
private const val ICON_PX = 96
