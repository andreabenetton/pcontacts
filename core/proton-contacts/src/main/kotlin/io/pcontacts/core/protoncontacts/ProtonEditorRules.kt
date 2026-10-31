// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors
// SPDX-FileCopyrightText: Proton AG and contributors
//   (derived from ProtonMail/WebClients: packages/shared/lib/contacts/constants.ts,
//    packages/shared/lib/contacts/property.ts,
//    packages/components/containers/contacts/edit/ContactEditModal.tsx; commit 8a24e55)

package io.pcontacts.core.protoncontacts

/** A kind of value [ProtonEditorRules.conform] changed; [key] is what Room keeps, never the value (ADR-0007). */
enum class ProtonAdjustment(val key: String) {
    NAME_SHORTENED("name_shortened"),
    FIRST_NAME_SHORTENED("first_name_shortened"),
    LAST_NAME_SHORTENED("last_name_shortened"),
    EMAIL_TRIMMED("email_trimmed");

    companion object {
        fun fromKey(key: String): ProtonAdjustment? = entries.firstOrNull { it.key == key }
    }
}

/** A contact as it will be sent, and what was changed to make it fit. */
data class Conformed(val contact: DecryptedContact, val adjustments: Set<ProtonAdjustment>)

/**
 * The checks Proton's web editor runs before it saves a contact, applied by conforming the value
 * instead of refusing it (ADR-0017, 2026-10-15, third amendment).
 *
 * `[V]` `CONTACT_NAME_MAX_LENGTH` is 190 ("the backend has a limit of 190 chars for a contact
 * name"; `[A]` a longer one is refused with HTTP 422, Code 2024, seen live 2026-10-15) and a first
 * or last name may have `190 / 2 - 1` = 94; the editor counts UTF-16 units of the trimmed value.
 * Its email check rejects surrounding spaces; anything else it would reject is sent as it is,
 * for Proton's own answer to decide.
 */
object ProtonEditorRules {
    const val NAME_MAX_LENGTH = 190
    const val FIRST_LAST_NAME_MAX_LENGTH = NAME_MAX_LENGTH / 2 - 1

    /**
     * [contact] fitted to the rules. Only values that differ from [onProton] (null for a new
     * contact) are touched: a value Proton holds is never rewritten. An adjustment whose result
     * Proton already holds is not reported again.
     */
    fun conform(contact: DecryptedContact, onProton: DecryptedContact? = null): Conformed {
        val adjustments = mutableSetOf<ProtonAdjustment>()
        fun fit(value: String?, held: String?, max: Int, kind: ProtonAdjustment): String? {
            if (value == null || value == held || value.trim().length <= max) return value
            val cut = cut(value.trim(), max)
            if (cut != held) adjustments += kind
            return cut
        }

        val name = contact.structuredName?.let { n ->
            val held = onProton?.structuredName
            n.copy(
                given = fit(n.given, held?.given, FIRST_LAST_NAME_MAX_LENGTH, ProtonAdjustment.FIRST_NAME_SHORTENED),
                family = fit(n.family, held?.family, FIRST_LAST_NAME_MAX_LENGTH, ProtonAdjustment.LAST_NAME_SHORTENED)
            )
        }
        val heldAddresses = onProton?.emails.orEmpty().mapTo(HashSet()) { it.address }
        val emails = contact.emails.map { email ->
            val trimmed = email.address.trim()
            if (trimmed == email.address || email.address in heldAddresses) {
                email
            } else {
                if (trimmed !in heldAddresses) adjustments += ProtonAdjustment.EMAIL_TRIMMED
                email.copy(address = trimmed)
            }
        }
        val conformed = contact.copy(
            fullName = fit(contact.fullName, onProton?.fullName, NAME_MAX_LENGTH, ProtonAdjustment.NAME_SHORTENED),
            structuredName = name,
            emails = emails
        )
        return Conformed(conformed, adjustments)
    }

    /** The first [max] UTF-16 units, never ending inside a surrogate pair, without trailing spaces. */
    private fun cut(value: String, max: Int): String {
        val end = if (Character.isHighSurrogate(value[max - 1])) max - 1 else max
        return value.substring(0, end).trimEnd()
    }
}
