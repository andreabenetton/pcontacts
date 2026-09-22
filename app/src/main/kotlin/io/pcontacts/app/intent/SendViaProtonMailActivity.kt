// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.intent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import io.pcontacts.app.R
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.core.contactswriter.PContactsMimeTypes

/**
 * ADR-0021: tap target for the "Send via Proton Mail" custom-MIMETYPE
 * row written by `:core:contacts-writer` alongside every Email row on
 * a Proton contact.
 *
 * Intent dispatch is layered:
 *
 *   1. **Proton Mail Android** (`ch.protonmail.android`) — preferred
 *      when installed. Fires `ACTION_SENDTO mailto:<email>` with the
 *      package set explicitly so Android can't substitute another
 *      mail client at the chooser.
 *   2. **Proton Mail web compose** — fallback when the Android app
 *      isn't installed or its activity isn't found. Opens
 *      `https://mail.proton.me/u/0/inbox#compose=true&to=<email>` in
 *      whatever browser the user has set as default. `[U]` — the
 *      fragment-based compose deeplink is the contemporary
 *      mail.proton.me convention; if Proton ever changes it, the
 *      activity surfaces a non-sensitive toast and falls through to
 *      the bare inbox URL.
 *
 * The activity carries `Theme.NoDisplay` + `noHistory=true`: it never
 * draws a window, never appears in the recents stack, and finishes as
 * soon as `startActivity` returns.
 *
 * The expected caller is Android's Contacts UI dispatching `ACTION_VIEW`
 * on the row's Data URI, but the activity is exported, so any app can
 * call it with any `content://` URI. It therefore acts only on a
 * ContactsContract Data row whose type is our MIME type and whose
 * RawContact belongs to the pcontacts account, and finishes quietly
 * otherwise — it never reads a foreign row under its own permission,
 * and never reveals whether one exists.
 */
class SendViaProtonMailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val email = resolveEmail()
        if (email == null) {
            finish()
            return
        }
        if (!tryProtonMailAndroid(email)) {
            launchWebCompose(email)
        }
        finish()
    }

    private fun resolveEmail(): String? {
        val uri = intent?.data?.takeIf(::isOurDataRow) ?: return null
        return contentResolver.query(
            uri,
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(PContactsMimeTypes.SEND_VIA_PROTON_MAIL, PROTON_ACCOUNT_TYPE),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf(::looksLikeEmail)
    }

    /** `content://com.android.contacts/data/<id>` whose provider-reported type is our chip MIME type. */
    private fun isOurDataRow(uri: Uri): Boolean {
        val contactsProvider = uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == ContactsContract.AUTHORITY
        val segments = uri.pathSegments
        val dataRow = segments.size == 2 && segments[0] == DATA_PATH && segments[1].toLongOrNull() != null
        return contactsProvider && dataRow && contentResolver.getType(uri) == PContactsMimeTypes.SEND_VIA_PROTON_MAIL
    }

    private fun looksLikeEmail(value: String): Boolean =
        value.length <= MAX_EMAIL_LENGTH && value.contains('@') && value.none { it.isWhitespace() }

    private fun tryProtonMailAndroid(email: String): Boolean {
        val sendIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))
            .setPackage(PROTON_MAIL_ANDROID_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(sendIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun launchWebCompose(email: String) {
        val encoded = Uri.encode(email)
        val url = "https://mail.proton.me/u/0/inbox#compose=true&to=$encoded"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            // No browser at all → bare inbox URL as the very last
            // fallback. If even that fails the device has no
            // ACTION_VIEW http handler, which is exotic enough that
            // a user-visible toast is enough.
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.proton.me"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.chip_send_via_proton_mail, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val PROTON_MAIL_ANDROID_PACKAGE = "ch.protonmail.android"

        /** The path of `ContactsContract.Data.CONTENT_URI`. */
        const val DATA_PATH = "data"

        /** RFC 5321 upper bound; anything longer is not an address the row should carry. */
        const val MAX_EMAIL_LENGTH = 254
    }
}
