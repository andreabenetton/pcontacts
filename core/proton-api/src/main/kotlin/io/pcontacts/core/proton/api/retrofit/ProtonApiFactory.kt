// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.retrofit

import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.Session
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.http.OkHttpClientFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Assembles the Retrofit instance and exposes typed API surfaces.
 * Keep the JSON config explicit so the wire shape can't drift silently
 * across kotlinx-serialization upgrades.
 */
class ProtonApiFactory(
    config: ProtonApiConfig,
    session: Session,
    client: OkHttpClient = OkHttpClientFactory.create(config, session)
) {
    private val json: Json = Json {
        ignoreUnknownKeys = true        // Server adds fields without notice; tolerate them.
        explicitNulls = false           // Proton omits nulls from JSON.
        coerceInputValues = true        // Tolerate missing optional numeric/list fields.
        encodeDefaults = true           // DTO defaults (e.g. Intent = "Proton") must reach the wire.
    }

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val auth: ProtonAuthApi = retrofit.create(ProtonAuthApi::class.java)
}
