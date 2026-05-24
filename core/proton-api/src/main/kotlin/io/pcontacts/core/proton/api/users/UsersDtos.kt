// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.users

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shapes for the user-key endpoints that drive the post-SRP key
 * unlock chain (Plan §2.7 steps 11–13):
 *
 *   1. GET core/v4/users         → User with Keys[].PrivateKey (armored)
 *   2. GET core/v4/keys/salts    → KeySalt per User.Keys[i].ID
 *   3. keyPassword = bcrypt-SHA512(password, salt-for-primary-key)
 *   4. BouncyCastleKeyUnlock(PrivateKey, keyPassword) → handles
 *
 * Verification markers:
 *   [V]   field names and types confirmed in
 *         packages/shared/lib/interfaces/User.ts + Key.ts
 *   [V]   salts endpoint path `core/v4/keys/salts` — confirmed by
 *         live API fetch (2026-05-24).
 */

@Serializable
data class UserKeyDto(
    @SerialName("ID") val id: String,
    @SerialName("Version") val version: Int = 0,
    @SerialName("Primary") val primary: Int = 0,
    @SerialName("Active") val active: Int = 1,
    @SerialName("PrivateKey") val privateKey: String,
    @SerialName("Fingerprint") val fingerprint: String = "",
    @SerialName("Flags") val flags: Int = 0
)

@Serializable
data class UserDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("DisplayName") val displayName: String = "",
    @SerialName("Email") val email: String = "",
    @SerialName("Keys") val keys: List<UserKeyDto> = emptyList()
)

@Serializable
data class GetUserResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("User") val user: UserDto
)

@Serializable
data class KeySaltDto(
    @SerialName("ID") val keyId: String,
    @SerialName("KeySalt") val keySalt: String? = null
)

@Serializable
data class GetKeySaltsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("KeySalts") val keySalts: List<KeySaltDto> = emptyList()
)
