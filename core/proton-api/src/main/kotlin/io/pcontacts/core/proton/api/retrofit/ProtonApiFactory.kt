// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.retrofit

import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.Session
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.http.OkHttpClientFactory
import io.pcontacts.core.proton.api.http.RefreshingAuthenticator
import io.pcontacts.core.proton.api.http.TokenRefresher
import io.pcontacts.core.proton.api.labels.ProtonLabelsApi
import io.pcontacts.core.proton.api.users.ProtonUsersApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Assembles the Retrofit instance and exposes typed API surfaces.
 * Keep the JSON config explicit so the wire shape can't drift silently
 * across kotlinx-serialization upgrades.
 *
 * When `refreshConfig` is supplied, the factory builds in two stages:
 *   1. A refresh-only OkHttpClient with NO authenticator — used
 *      solely for the `/auth/refresh` call so the authenticator
 *      can't recurse.
 *   2. The main OkHttpClient with `RefreshingAuthenticator` wired
 *      against the stage-1 refresh API.
 *
 * When `refreshConfig` is null (tests, the pre-login bootstrap
 * path), the single-stage client without authenticator is used —
 * same as before.
 */
class ProtonApiFactory(
    config: ProtonApiConfig,
    session: Session,
    refreshConfig: RefreshConfig? = null
) {

    /**
     * Caller-supplied 401 → /auth/refresh wiring. The factory keeps
     * :core:proton-api independent of :core:storage by taking the
     * persistence side as callbacks rather than depending on
     * SecretStore directly.
     */
    data class RefreshConfig(
        /** Same instance carried by the read-only `session` param. */
        val mutableSession: InMemorySession,
        /** Returns the current refresh_token, or null if logged out. */
        val getRefreshToken: () -> String?,
        /** Hook to persist the freshly-rotated tokens (typically into SecretStore). */
        val onTokensRefreshed: (accessToken: String, refreshToken: String) -> Unit
    )

    private val json: Json = Json {
        ignoreUnknownKeys = true        // Server adds fields without notice; tolerate them.
        explicitNulls = false           // Proton omits nulls from JSON.
        coerceInputValues = true        // Tolerate missing optional numeric/list fields.
        encodeDefaults = true           // DTO defaults (e.g. Intent = "Proton") must reach the wire.
    }

    // Stage 1: refresh-only client — no authenticator. The auth API surface
    // built on top of this client is reserved for the refresher itself so
    // /auth/refresh can never recurse through the authenticator.
    private val refreshOnlyClient: OkHttpClient =
        OkHttpClientFactory.create(config, session, authenticator = null)
    private val refreshOnlyAuthApi: ProtonAuthApi =
        buildRetrofit(config, refreshOnlyClient).create(ProtonAuthApi::class.java)

    // Stage 2: main client. When refreshConfig is wired, includes the
    // RefreshingAuthenticator that consumes refreshOnlyAuthApi. Otherwise
    // it's the same client as stage 1.
    private val mainClient: OkHttpClient = if (refreshConfig != null) {
        val refresher = TokenRefresher(
            refreshOnlyAuthApi = refreshOnlyAuthApi,
            mutableSession = refreshConfig.mutableSession,
            getRefreshToken = refreshConfig.getRefreshToken,
            onTokensRefreshed = refreshConfig.onTokensRefreshed
        )
        OkHttpClientFactory.create(
            config = config,
            session = session,
            authenticator = RefreshingAuthenticator(refresher, refreshConfig.mutableSession)
        )
    } else {
        refreshOnlyClient
    }

    private val retrofit: Retrofit = buildRetrofit(config, mainClient)

    val auth: ProtonAuthApi = retrofit.create(ProtonAuthApi::class.java)
    val contacts: ProtonContactsApi = retrofit.create(ProtonContactsApi::class.java)
    val users: ProtonUsersApi = retrofit.create(ProtonUsersApi::class.java)
    val labels: ProtonLabelsApi = retrofit.create(ProtonLabelsApi::class.java)

    private fun buildRetrofit(config: ProtonApiConfig, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
