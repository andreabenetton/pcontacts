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
 *   [U] exact ChallengePayload algorithm — see ADR-0014 §"Open Questions"
 *       and the implementation plan §2.8. For now `Payload` is typed as
 *       `Map<String, String>` so we can stub it during early integration.
 *   [A] computeKeyPassword parameters — bcrypt-SHA512 cost factor not
 *       exhaustively documented in the public source. ADR-0013 captures
 *       vectors as soon as the Node script lands.
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
    // [U] ChallengePayload — see file-level doc.
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
