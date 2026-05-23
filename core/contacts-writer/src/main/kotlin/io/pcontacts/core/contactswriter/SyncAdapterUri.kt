// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.net.Uri
import android.provider.ContactsContract

/**
 * Every ContactsContract write originating from a SyncAdapter MUST carry
 * `?caller_is_syncadapter=true` (ADR-0010). Without it, deletes leave a
 * tombstone the next sync resurrects as a duplicate.
 *
 * This is the canonical helper; per ADR-0010 / CLAUDE.md anti-patterns,
 * no other module may build a ContactsContract write URI without going
 * through it. (A detekt / lint rule will enforce this once
 * `:tools:lint` grows the check.)
 */
object SyncAdapterUri {

    fun decorate(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
            .build()
}
