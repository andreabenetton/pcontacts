// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger

/**
 * Placeholder SyncAdapter (ADR-0004). For now `onPerformSync` writes two
 * hardcoded `RawContacts` rows under the Proton account so we can prove the
 * ContactsContract pipeline end-to-end on a real device before any Proton
 * network calls land. The real engine (`:core:sync`) replaces this body in
 * a later commit.
 *
 * Idempotency: each fake contact has a stable `SOURCE_ID`; subsequent syncs
 * detect the existing rows and skip the insert. No duplicates.
 */
class ProtonSyncAdapter(
    context: Context,
    autoInitialize: Boolean = true
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    private val logger: Logger = RedactingLogger(tag = "ProtonSync", sink = NoOpSink)

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        logger.info { "sync start account=${account.name} authority=$authority" }

        val existingSourceIds = readExistingSourceIds(provider, account)
        val toInsert = FAKE_CONTACTS.filterNot { existingSourceIds.contains(it.sourceId) }

        if (toInsert.isEmpty()) {
            logger.info { "sync done — no new contacts" }
            return
        }

        val ops = ArrayList<ContentProviderOperation>()
        toInsert.forEach { fc ->
            val rawIdx = ops.size
            ops += newRawContactInsert(account, fc.sourceId)
            ops += newStructuredNameInsert(rawIdx, fc.given, fc.family)
            ops += newEmailInsert(rawIdx, fc.email)
        }

        try {
            provider.applyBatch(ops)
            syncResult.stats.numInserts += toInsert.size.toLong()
            logger.info { "sync inserted ${toInsert.size} fake contacts" }
        } catch (t: Throwable) {
            syncResult.stats.numIoExceptions += 1
            logger.error(t) { "sync apply failed" }
        }
    }

    private fun readExistingSourceIds(
        provider: ContentProviderClient,
        account: Account
    ): Set<String> {
        val sourceIds = mutableSetOf<String>()
        provider.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.SOURCE_ID),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.let { sourceIds += it }
            }
        }
        return sourceIds
    }

    private fun newRawContactInsert(account: Account, sourceId: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(
            RawContacts.CONTENT_URI.asSyncAdapter(account.name, account.type)
        )
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(RawContacts.SOURCE_ID, sourceId)
            .build()

    private fun newStructuredNameInsert(rawIdx: Int, given: String, family: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.GIVEN_NAME, given)
            .withValue(StructuredName.FAMILY_NAME, family)
            .withValue(StructuredName.DISPLAY_NAME, "$given $family")
            .build()

    private fun newEmailInsert(rawIdx: Int, email: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, email)
            .withValue(Email.TYPE, Email.TYPE_OTHER)
            .build()

    private data class FakeContact(
        val sourceId: String,
        val given: String,
        val family: String,
        val email: String
    )

    companion object {
        // Placeholder fixtures. Replaced by the real Proton sync engine
        // (:core:sync) before any user-visible release. SOURCE_ID values are
        // namespaced with 'fake-' so they're easy to grep/clean up later.
        private val FAKE_CONTACTS = listOf(
            FakeContact("fake-1", "Test Proton", "One", "one@proton.example"),
            FakeContact("fake-2", "Test Proton", "Two", "two@proton.example")
        )
    }
}
