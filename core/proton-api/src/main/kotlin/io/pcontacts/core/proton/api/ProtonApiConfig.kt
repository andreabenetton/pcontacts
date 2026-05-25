// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api

/**
 * Static configuration the HTTP layer needs: base URL, the
 * `x-pm-appversion` value, optional locale.
 *
 * `[V]` The server validates `x-pm-appversion` against a sliding
 * window of accepted `android-mail@<semver>` values (custom client
 * IDs are rejected with HTTP 400). As of 2026-05-24, `2.0.0` through
 * `3.0.12` are accepted; below returns 422, above returns 401. The
 * window moves as Proton ships new official releases, so this default
 * will need periodic bumps.
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
