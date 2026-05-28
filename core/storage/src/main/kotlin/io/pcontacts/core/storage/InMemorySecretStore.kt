// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

/**
 * Test-only `SecretStore`. The orchestrator tests in :core:sync and
 * the login UI tests use this in place of `EncryptedSecretStore` so they
 * stay pure-JVM (no Robolectric / no emulator).
 *
 * Not thread-safe by intent — wrap with synchronization at the boundary
 * if a test scenario needs concurrent access.
 */
class InMemorySecretStore : SecretStore {

    private var uid: String? = null
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var keyPassword: ByteArray? = null
    private var humanVerificationToken: String? = null
    private var humanVerificationTokenType: String? = null

    override fun uid(): String? = uid
    override fun setUid(value: String?) { uid = value }

    override fun accessToken(): String? = accessToken
    override fun setAccessToken(value: String?) { accessToken = value }

    override fun refreshToken(): String? = refreshToken
    override fun setRefreshToken(value: String?) { refreshToken = value }

    override fun keyPassword(): ByteArray? = keyPassword?.copyOf()
    override fun setKeyPassword(value: ByteArray?) {
        // Zero the previous bytes before overwriting the reference.
        keyPassword?.fill(0)
        keyPassword = value?.copyOf()
    }

    override fun humanVerificationToken(): String? = humanVerificationToken
    override fun setHumanVerificationToken(value: String?) { humanVerificationToken = value }
    override fun humanVerificationTokenType(): String? = humanVerificationTokenType
    override fun setHumanVerificationTokenType(value: String?) { humanVerificationTokenType = value }

    override fun logout() {
        uid = null
        accessToken = null
        refreshToken = null
        keyPassword?.fill(0)
        keyPassword = null
        humanVerificationToken = null
        humanVerificationTokenType = null
    }
}
