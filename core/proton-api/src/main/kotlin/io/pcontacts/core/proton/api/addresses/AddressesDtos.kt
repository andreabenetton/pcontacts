// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.addresses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shapes for the `core/v4/addresses` endpoint. Address keys are the
 * second key class our decrypt path must handle: contacts can be
 * encrypted to either a user key OR an address key, and real Proton
 * mailboxes commonly use the latter.
 *
 * Each [AddressKeyDto] carries a PGP-armored `Token` (encrypted to the
 * user's primary key) and a detached `Signature` over the decrypted
 * Token. The address-key passphrase is the decrypted-Token bytes; see
 * [V] WebClients `packages/shared/lib/keys/keys.ts:decryptAddressKeyToken`.
 *
 * Verification markers:
 *   [V] field names and types confirmed in
 *       packages/shared/lib/interfaces/Key.ts and Address.ts
 *   [A] `Receive` / `Send` flags carried for completeness; the decrypt
 *       path ignores them.
 *   [U] legacy v1 address keys can omit `Token` / `Signature` —
 *       handled by nullable defaults; live behaviour against a fresh
 *       address-key generation needs validation.
 */

@Serializable
data class AddressKeyDto(
    @SerialName("ID") val id: String,
    @SerialName("AddressID") val addressId: String = "",
    @SerialName("Primary") val primary: Int = 0,
    @SerialName("Active") val active: Int = 1,
    @SerialName("Flags") val flags: Int = 0,
    @SerialName("PrivateKey") val privateKey: String,
    @SerialName("Token") val token: String? = null,
    @SerialName("Signature") val signature: String? = null,
    @SerialName("Fingerprint") val fingerprint: String = ""
)

@Serializable
data class AddressDto(
    @SerialName("ID") val id: String,
    @SerialName("Email") val email: String = "",
    @SerialName("Status") val status: Int = 1,
    @SerialName("Receive") val receive: Int = 1,
    @SerialName("Send") val send: Int = 1,
    @SerialName("Keys") val keys: List<AddressKeyDto> = emptyList()
)

@Serializable
data class GetAddressesResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Addresses") val addresses: List<AddressDto> = emptyList()
)
