// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.Data

/**
 * The account some apps save contacts under that no authenticator
 * registers, so Android purges its rows at the next account change
 * (ADR-0026). Exactly this type and name; nothing broader.
 */
object OrphanPhoneAccount {
    const val TYPE = "PHONE"
    const val NAME = "PHONE"

    val account: Account get() = Account(NAME, TYPE)

    fun matches(accountType: String?, accountName: String?): Boolean = accountType == TYPE && accountName == NAME
}

/** Standard details the Proton model does not carry: a move would lose them (ADR-0026). */
enum class UncarriedKind { EVENT, RELATION, SIP_ADDRESS }

/**
 * Moves an orphan `PHONE` RawContact into the pcontacts account
 * (ADR-0026) — the one write pcontacts makes to another account's row.
 * The row keeps its `_ID`, Data rows and the Contact's state; the
 * outbox then creates it on Proton.
 */
class OrphanContactMover(private val provider: ContentProviderClient) {

    /** True when exactly that row moved; false when it is gone or no longer in the orphan account. */
    fun move(target: Account, rawContactId: Long): Boolean {
        val results = provider.applyBatch(arrayListOf(ContactsContractOps.buildMoveOrphan(target, rawContactId)))
        return results.singleOrNull()?.count == 1
    }

    /** The standard details of [rawContactId] that would not survive the move. */
    fun uncarried(rawContactId: Long): List<UncarriedKind> {
        val cursor = provider.query(
            Data.CONTENT_URI,
            arrayOf(Data.MIMETYPE, Data.DATA2),
            "${Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        ) ?: return emptyList()
        val rows = cursor.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to (if (c.isNull(1)) null else c.getInt(1))) }
        }
        return uncarriedKinds(rows)
    }

    companion object {
        /** Pure — (mimetype, DATA2) per Data row; visible for tests. */
        fun uncarriedKinds(rows: List<Pair<String?, Int?>>): List<UncarriedKind> = rows.mapNotNull { (mime, type) ->
            when (mime) {
                Event.CONTENT_ITEM_TYPE ->
                    UncarriedKind.EVENT.takeIf { type != Event.TYPE_BIRTHDAY && type != Event.TYPE_ANNIVERSARY }
                Relation.CONTENT_ITEM_TYPE -> UncarriedKind.RELATION
                SipAddress.CONTENT_ITEM_TYPE -> UncarriedKind.SIP_ADDRESS
                else -> null
            }
        }.distinct()
    }
}
