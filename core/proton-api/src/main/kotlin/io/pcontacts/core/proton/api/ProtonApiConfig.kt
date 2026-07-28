// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api

/**
 * Static configuration the HTTP layer needs: base URL, the
 * `x-pm-appversion` value, optional locale.
 *
 * The `x-pm-appversion` value is a client identifier that selects a
 * server-side API *contract* — it is NOT "the latest app version".
 * `[V]` The server accepts `android-mail@<semver>` for the window that
 * matches the direct `auth/info` SRP flow this app implements:
 * **2.0.0 through 3.0.12**. Verified live against `POST core/v4/auth/info`
 * on 2026-07-28 — `1.0.0` → 422 (Code 5003, force upgrade),
 * `2.0.0`/`3.0.12` → 200 + `Modulus`, `3.0.13` and up (including the
 * current 7.x line) → 401 "Invalid access token".
 * `[U]` 3.0.13+ appear to require an unauthenticated-session token
 * obtained before `auth/info`, which this app does not implement — so
 * do NOT bump this to the latest official android-mail release.
 *
 * When the pinned version ages out, Proton responds with `Code: 5003`
 * (force upgrade) `[V]` or `5004` (API version unsupported) `[A]`.
 * [io.pcontacts.core.proton.api.http.AppVersionRejectionInterceptor]
 * detects these codes and throws
 * [io.pcontacts.core.proton.api.http.AppVersionRejectedException],
 * letting callers distinguish "needs app update" from transient IO
 * errors. See `docs/API_RESEARCH.md` §2 for the full validation
 * matrix.
 */
data class ProtonApiConfig(
    val baseUrl: String = "https://mail-api.proton.me/",
    val appVersion: String = "android-mail@$DEFAULT_APP_VERSION",
    val locale: String? = null
) {
    init {
        require(baseUrl.endsWith("/")) { "baseUrl must end with '/'" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
    }

    companion object {
        const val DEFAULT_APP_VERSION: String = "3.0.12"
    }
}
