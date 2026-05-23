// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * Maps vCard `TEL;TYPE=...` token sequences to the MVP `PhoneType`
 * enum and then to `ContactsContract.CommonDataKinds.Phone.TYPE_*`.
 *
 * Pair-aware: `["fax","home"]` → `FAX_HOME`, not `FAX_*` or `HOME`.
 * Unknown / unrecognised tokens collapse to `OTHER`; CUSTOM + LABEL
 * support is deferred to the complete version (plan §6).
 *
 * Token recognition is case-insensitive — Proton's web client emits
 * lower-case tokens but some imported vCards arrive upper-case.
 */
internal object PhoneTypeMapper {

    fun fromTokens(rawTokens: List<String>): PhoneType {
        val tokens = rawTokens.asSequence().map { it.lowercase() }.toSet()

        // Pair-aware: fax + (home/work) → typed fax.
        val isFax = "fax" in tokens
        return when {
            isFax && "home" in tokens -> PhoneType.FAX_HOME
            isFax && "work" in tokens -> PhoneType.FAX_WORK
            isFax -> PhoneType.OTHER          // bare "fax" has no Android equivalent
            "cell" in tokens || "mobile" in tokens -> PhoneType.MOBILE
            "home" in tokens -> PhoneType.HOME
            "work" in tokens -> PhoneType.WORK
            "pager" in tokens -> PhoneType.PAGER
            "main" in tokens -> PhoneType.MAIN
            else -> PhoneType.OTHER
        }
    }

    fun toAndroid(type: PhoneType): Int = when (type) {
        PhoneType.HOME -> Phone.TYPE_HOME
        PhoneType.WORK -> Phone.TYPE_WORK
        PhoneType.MOBILE -> Phone.TYPE_MOBILE
        PhoneType.FAX_HOME -> Phone.TYPE_FAX_HOME
        PhoneType.FAX_WORK -> Phone.TYPE_FAX_WORK
        PhoneType.PAGER -> Phone.TYPE_PAGER
        PhoneType.MAIN -> Phone.TYPE_MAIN
        PhoneType.OTHER -> Phone.TYPE_OTHER
    }
}
