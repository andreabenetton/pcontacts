// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the wire-level shapes the Proton web client uses for the auth
 * endpoints. Field names match the JSON exactly; capitalization matters.
 *
 * Verification status:
 *   [V] field names + endpoint paths confirmed in
 *       ProtonMail/WebClients packages/shared/lib/api/auth.ts
 *   [V] ChallengePayload — empty map accepted by live API (2026-05-24).
 *   [V] computeKeyPassword parameters — bcrypt cost 10, salt from
 *       keys/salts endpoint. Validated end-to-end (2026-05-24).
 */

@Serializable
data class InfoRequest(
    @SerialName("Username") val username: String,
    @SerialName("Intent") val intent: String = "Proton"
)

@Serializable
data class InfoResponse(
    @SerialName("Modulus") val modulus: String,
    @SerialName("ServerEphemeral") val serverEphemeral: String,
    @SerialName("Version") val version: Int,
    @SerialName("Salt") val salt: String,
    @SerialName("SRPSession") val srpSession: String,
    @SerialName("Code") val code: Int = 0
)

@Serializable
data class AuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("ClientEphemeral") val clientEphemeral: String,
    @SerialName("ClientProof") val clientProof: String,
    @SerialName("SRPSession") val srpSession: String,
    // [V] ChallengePayload — empty map accepted (2026-05-24).
    @SerialName("Payload") val payload: Map<String, String>? = null,
    @SerialName("PersistentCookies") val persistentCookies: Int? = null
)

@Serializable
data class AuthResponse(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("TokenType") val tokenType: String,
    @SerialName("ExpiresIn") val expiresIn: Long,
    @SerialName("UID") val uid: String,
    @SerialName("UserID") val userId: String,
    @SerialName("LocalID") val localId: Long? = null,
    @SerialName("PasswordMode") val passwordMode: Int,
    @SerialName("TwoFactor") val twoFactor: Int,
    @SerialName("ServerProof") val serverProof: String,
    @SerialName("Scopes") val scopes: List<String> = emptyList(),
    @SerialName("Code") val code: Int = 0
)

@Serializable
data class TwoFactorRequest(
    @SerialName("TwoFactorCode") val twoFactorCode: String
)

/**
 * Response to `core/v4/auth/2fa`. The server does not re-issue tokens —
 * it elevates the existing session's scope (the access token gains the
 * "full" scope on success) and returns only `{Code, Scopes}`.
 *   [V] shape confirmed in packages/shared/lib/api/auth.ts (`submitTOTP`,
 *       `submitFido2`) — the response is consumed for its scope set, not
 *       its tokens.
 */
@Serializable
data class TwoFactorResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Scopes") val scopes: List<String> = emptyList()
)

@Serializable
data class RefreshRequest(
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("ResponseType") val responseType: String = "token",
    @SerialName("GrantType") val grantType: String = "refresh_token"
)

@Serializable
data class RefreshResponse(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("TokenType") val tokenType: String,
    @SerialName("ExpiresIn") val expiresIn: Long,
    @SerialName("UID") val uid: String,
    @SerialName("Scopes") val scopes: List<String> = emptyList(),
    @SerialName("Code") val code: Int = 0
)
