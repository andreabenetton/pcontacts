// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import ezvcard.Ezvcard
import ezvcard.io.scribe.ScribeIndex

/**
 * The structure of a card for the log: its type and the names of its
 * properties with their groups and parameter names — never a value.
 * "SIGNED[FN,UID,item1.EMAIL;PREF]" says what Proton was handed when it
 * refuses an update; the contact itself stays out of the log.
 */
internal object CardShape {
    private val scribes = ScribeIndex()

    fun of(type: CardType, text: String): String {
        val vcard = Ezvcard.parse(text).first() ?: return "$type[unparsable]"
        val names = vcard.properties.map { p ->
            val name = scribes.getPropertyScribe(p)?.propertyName ?: p.javaClass.simpleName
            val group = p.group?.let { "$it." }.orEmpty()
            val params = p.parameters.map { it.key }.sorted().joinToString(";")
            if (params.isEmpty()) "$group$name" else "$group$name;$params"
        }
        return "$type[${names.joinToString(",")}]"
    }
}
