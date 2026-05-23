// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

/**
 * One Card after dispatch + crypto: the plaintext vCard fragment plus
 * the per-card verification verdict. `originalType` is preserved so the
 * merger can apply the §10.3 rule "discard UID properties from
 * non-SIGNED cards".
 *
 * Per Plan §10.1 / ADR-0007, a card whose signature fails verification
 * retains its decrypted plaintext with `verified = false` rather than
 * being dropped — losing data silently is worse than surfacing a
 * downgrade indicator.
 */
data class DecryptedCard(
    val originalType: CardType,
    val plaintext: String,
    val verified: Boolean
)
