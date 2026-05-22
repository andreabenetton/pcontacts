// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api

/**
 * Read-only handle to the current Proton session, surfaced to the HTTP
 * interceptors so they can attach `x-pm-uid` and `Authorization` when
 * present (or omit them when the request is unauthenticated, e.g.
 * `core/v4/auth/info`).
 *
 * The session lifecycle (creation on login, rotation on refresh, wipe on
 * logout) lives in `:core:storage`'s SecretStore (ADR-0009). This
 * interface keeps `:core:proton-api` independent of that storage layer.
 */
interface Session {
    /** Proton session UID, or null if the user is not logged in. */
    fun uid(): String?

    /** Current AccessToken, or null if no session or not yet refreshed. */
    fun accessToken(): String?
}

/** In-memory session for tests and the unauthenticated bootstrap path. */
class InMemorySession(
    @Volatile private var uid: String? = null,
    @Volatile private var accessToken: String? = null
) : Session {

    override fun uid(): String? = uid
    override fun accessToken(): String? = accessToken

    fun update(uid: String?, accessToken: String?) {
        this.uid = uid
        this.accessToken = accessToken
    }

    fun clear() {
        uid = null
        accessToken = null
    }
}
