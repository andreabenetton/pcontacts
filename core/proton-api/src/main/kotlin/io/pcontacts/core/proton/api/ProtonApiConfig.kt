// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api

/**
 * Static configuration the HTTP layer needs: base URL, the
 * `x-pm-appversion` value, optional locale.
 *
 * `appVersion` is `[A]` until validated against a live Proton account —
 * the server validates a list of accepted client IDs server-side and may
 * reject unknown ones. See docs/adr/0012-http-stack-okhttp-retrofit.md
 * §"Mandatory headers".
 */
data class ProtonApiConfig(
    val baseUrl: String = "https://api.proton.me/",
    val appVersion: String = "android-contacts@$DEFAULT_APP_VERSION",
    val locale: String? = null
) {
    init {
        require(baseUrl.endsWith("/")) { "baseUrl must end with '/'" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
    }

    companion object {
        const val DEFAULT_APP_VERSION: String = "0.0.1"
    }
}
