// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.EmailType
import io.pcontacts.core.contactswriter.EmailTypeMapper
import io.pcontacts.core.protoncontacts.DecryptedContact

/**
 * The one-time rewrite after the hash format rolled to v3 must not destroy
 * what the new fields exist to save (ADR-0023, 2026-09-24): a birthday,
 * anniversary, nickname or website the phone holds but Proton lacks was
 * never pushed, because earlier versions did not see it. Before that
 * rewrite the phone's values are compared with the server's. The roll to
 * v4 does the same for an email's home/work type (ADR-0023, 2026-10-15).
 */
internal object ExtraFieldsGuard {

    sealed interface Verdict {
        /** Nothing only the phone holds: rewrite from the server as usual. */
        data object Rewrite : Verdict

        /** The phone holds values Proton lacks: push them first, rewrite afterwards. */
        data object PushFirst : Verdict

        /** Both sides hold a different birthday or anniversary: the user decides. */
        data class Clash(val fields: List<String>) : Verdict
    }

    fun judge(local: ContactRow, server: DecryptedContact): Verdict {
        val serverTypes = server.emails.groupBy { it.address }.mapValues { (_, same) ->
            EmailTypeMapper.fromTokens(same.flatMap { it.types })
        }
        // The phone's emails typed home or work, against the type Proton holds for the address.
        val typed = local.emailTypes.mapNotNull { (address, type) -> serverTypes[address]?.let { type to it } }
        val clashes = listOfNotNull(
            "birthday".takeIf { differ(local.birthday, server.birthday) },
            "anniversary".takeIf { differ(local.anniversary, server.anniversary) },
            "email type".takeIf { typed.any { (here, there) -> there != EmailType.OTHER && there != here } }
        )
        if (clashes.isNotEmpty()) return Verdict.Clash(clashes)
        val datesOnlyHere = (local.birthday != null && server.birthday == null) ||
            (local.anniversary != null && server.anniversary == null)
        val valuesOnlyHere = (local.nicknames - server.nicknames.toSet()).isNotEmpty() ||
            (local.websites - server.websites.toSet()).isNotEmpty()
        val typesOnlyHere = typed.any { (_, there) -> there == EmailType.OTHER }
        return if (datesOnlyHere || valuesOnlyHere || typesOnlyHere) Verdict.PushFirst else Verdict.Rewrite
    }

    private fun differ(local: String?, server: String?): Boolean = local != null && server != null && local != server
}
