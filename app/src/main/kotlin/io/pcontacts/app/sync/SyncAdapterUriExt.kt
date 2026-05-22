// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.net.Uri
import android.provider.ContactsContract

/**
 * Every ContactsContract write originating from a SyncAdapter MUST carry
 * `?caller_is_syncadapter=true`. Without it, Android writes a tombstone for
 * deletes and the next sync resurrects the row as a duplicate (ADR-0010).
 *
 * The contacts-writer module owns the canonical helper once it lands; this
 * shim keeps the :app-level scaffold honest in the meantime.
 */
internal fun Uri.asSyncAdapter(accountName: String, accountType: String): Uri =
    buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
        .build()
