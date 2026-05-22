// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

/**
 * Identifier for the Android `Account` and the `RawContacts.ACCOUNT_TYPE`
 * column. Kept stable across releases — changing it would orphan every
 * RawContacts row the app has ever written.
 */
const val PROTON_ACCOUNT_TYPE: String = "io.pcontacts.account"

/**
 * Auth-token type label surfaced to system AccountManager. The actual token
 * value is the Proton API AccessToken stored in `:core:storage`.
 */
const val PROTON_AUTH_TOKEN_TYPE: String = "io.pcontacts.access_token"
