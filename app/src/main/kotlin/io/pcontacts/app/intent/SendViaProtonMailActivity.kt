// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.intent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import io.pcontacts.app.R

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
 * Caller is Android's Contacts UI (Fossify, AOSP Contacts) dispatching
 * `ACTION_VIEW` on the row's Data URI. The address to compose to lives
 * in `Data.DATA1` per [PContactsMimeTypes.SEND_VIA_PROTON_MAIL].
 */
class SendViaProtonMailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val email = resolveEmail()
        if (email.isNullOrBlank()) {
            // Row malformed — nothing actionable, but don't crash. Toast
            // is intentional so a future MIMETYPE-schema regression
            // surfaces rather than failing silently.
            Toast.makeText(this, R.string.chip_send_via_proton_mail, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!tryProtonMailAndroid(email)) {
            launchWebCompose(email)
        }
        finish()
    }

    private fun resolveEmail(): String? {
        val data = intent?.data ?: return null
        return contentResolver.query(
            data,
            arrayOf(ContactsContract.Data.DATA1),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun tryProtonMailAndroid(email: String): Boolean {
        val sendIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
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
    }
}
