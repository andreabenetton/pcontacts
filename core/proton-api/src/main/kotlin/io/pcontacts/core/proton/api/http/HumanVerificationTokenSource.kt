// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

/**
 * Read/clear surface for the captcha verification token that Proton's
 * `x-pm-human-verification-token` + `x-pm-human-verification-token-type`
 * headers carry. Kept as an interface so `:core:proton-api` doesn't
 * depend on `:core:storage` directly (the production implementation
 * wraps a `SecretStore` and is wired at the next layer up).
 *
 * `[V]` Header names from
 * `ProtonMail/protoncore_android/network/data/.../ProtonApiBackend.kt::prepareHeaders`.
 */
interface HumanVerificationTokenSource {

    /** Opaque verification token, or null when no captcha has been solved. */
    fun token(): String?

    /** Token type — `"captcha"`, `"email"`, `"sms"`, `"payment"`, etc. */
    fun tokenType(): String?

    /**
     * Clears the stored token. Called by [HumanVerificationInterceptor]
     * when a 9001 fires on a request that already carried the headers
     * (stale-token recovery), and by [SecretStore.logout].
     */
    fun clear()

    /** No-op source — every `*Test` and the refresh-only OkHttpClient. */
    object Empty : HumanVerificationTokenSource {
        override fun token(): String? = null
        override fun tokenType(): String? = null
        override fun clear() = Unit
    }
}
