// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.contacts

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentValues
import android.provider.ContactsContract
import io.pcontacts.core.contactswriter.SyncAdapterUri
import io.pcontacts.core.logging.Logger

/**
 * Initializes the account-level Contacts Provider settings row so that
 * ungrouped contacts are visible and the account participates in sync.
 *
 * [V] AOSP's ContactsProvider defaults `ungrouped_visible=0` for any
 * sync-adapter-owned account (ContactsDatabaseHelper; on Android 12+ a
 * column default on the accounts table, on ≤11 a missing settings row
 * evaluates to invisible). Without this row, Proton contacts that carry
 * no label sync correctly but never appear in the device Contacts app.
 *
 * [V] ContactsProvider2.insertSettings treats an insert for an existing
 * account as an update (upsert on account_name/account_type), so calling
 * this after login and again before every sync is idempotent.
 *
 * [V] DAVx5 — including Mudita's unmodified fork shipped on the Kompakt —
 * writes the identical row (`SHOULD_SYNC=1`, `UNGROUPED_VISIBLE=1`) via
 * the same Settings insert on every address-book create/update, which is
 * why its contacts are visible on the same devices.
 *
 * Failure is non-fatal: OEM providers that reject or incompletely
 * implement Settings writes must not break login or sync. The caller
 * logs the `false` result and continues; a later sync retries.
 */
object ContactsAccountSettings {

    fun ensureVisibleAndSyncable(
        resolver: ContentResolver,
        account: Account,
        logger: Logger? = null
    ): Boolean {
        val uri = SyncAdapterUri.decorate(
            ContactsContract.Settings.CONTENT_URI,
            account.name,
            account.type
        )
        val values = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        }
        return try {
            resolver.insert(uri, values)
            true
        } catch (e: RuntimeException) {
            logger?.error(e) { "unable to initialize contacts Settings row; sync continues" }
            false
        }
    }
}
