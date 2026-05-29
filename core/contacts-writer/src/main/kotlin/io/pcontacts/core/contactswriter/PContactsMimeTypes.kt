// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * Custom ContactsContract.Data MIMETYPEs owned by pcontacts.
 *
 * Each constant declares the contract for one custom Data row written
 * alongside the standard Email / Phone / etc. rows. Contacts apps
 * (Fossify, AOSP Contacts) discover the row, look up the matching
 * intent-filter in our manifest, and render the chip with the
 * activity's icon + label. Tap dispatches an ACTION_VIEW Intent into
 * the activity, which extracts the row's DATA1 column and acts on it.
 *
 * **Stability promise:** these MIMETYPE strings are persisted in the
 * device's ContactsProvider for every Proton contact synced. Renaming
 * one orphans every row already on disk (the next sync rewrites them,
 * but during the gap the old chips disappear from Contacts apps).
 * Treat them as wire-format strings: deprecate via a new constant
 * rather than mutating an existing one.
 */
object PContactsMimeTypes {

    /**
     * Per-email chip "Send via Proton Mail". Written once per email
     * address on a Proton contact. DATA1 holds the address to compose
     * to; DATA2 holds the chip's summary text (the activity's
     * `android:label` overrides this in practice, but DATA2 keeps the
     * row self-describing for content-resolver consumers).
     *
     * Tap target: the SendViaProtonMailActivity in `:app`. See
     * ADR-0021.
     */
    const val SEND_VIA_PROTON_MAIL =
        "vnd.android.cursor.item/vnd.io.pcontacts.send_via_proton_mail"
}
