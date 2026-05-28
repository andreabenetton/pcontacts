// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.proton.api.http.HumanVerificationTokenSource
import io.pcontacts.core.storage.SecretStore

/**
 * Bridges `:core:storage`'s [SecretStore] to the OkHttp-layer
 * [HumanVerificationTokenSource] seam in `:core:proton-api`. Lives in
 * `:core:sync` because that's where both sides are reachable without
 * forming a dependency cycle (`:core:proton-api` must not depend on
 * `:core:storage` per ADR-0011's module-boundary rules).
 *
 * Wired into [AuthBootstrap] / [SyncBootstrap] when constructing the
 * `ProtonApiFactory`. Once the HV WebView Activity writes a token via
 * `SecretStore.setHumanVerificationToken*`, every subsequent OkHttp
 * request automatically attaches
 * `x-pm-human-verification-token{,-type}`.
 */
class SecretStoreHumanVerificationSource(
    private val secretStore: SecretStore
) : HumanVerificationTokenSource {

    override fun token(): String? = secretStore.humanVerificationToken()
    override fun tokenType(): String? = secretStore.humanVerificationTokenType()

    override fun clear() {
        secretStore.setHumanVerificationToken(null)
        secretStore.setHumanVerificationTokenType(null)
    }
}
