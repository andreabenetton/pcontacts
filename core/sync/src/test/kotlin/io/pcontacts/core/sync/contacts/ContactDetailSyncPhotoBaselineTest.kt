// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.protoncontacts.DecryptedContactJson
import io.pcontacts.core.protoncontacts.PhotoHash
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's photo digest in the merge base the pull saves (review 2026-11-17, F4). */
class ContactDetailSyncPhotoBaselineTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun a_photo_changed_on_the_phone_right_after_the_pull_is_not_taken_as_the_baseline() = runTest {
        // Review 2026-11-17, F4: the digest read may already be the user's new photo. A row dirty
        // after the read has an unknown local baseline, so the new photo still counts as a change.
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L))),
            contacts = mapOf("c1" to contact("c1", 100L, "BEGIN:VCARD\nVERSION:4.0\nFN:Alice\nEND:VCARD"))
        )
        val dao = DetailFakeContactMapDao()
        var editedAfterRead = false
        val engine = newEngine(
            api,
            dao,
            DetailFakeApplier(base = 1L),
            readLocalPhotoHash = {
                editedAfterRead = true
                "digest-of-the-new-photo"
            },
            readDirtyRawIds = { if (editedAfterRead) setOf(1L) else emptySet() }
        )

        engine.sync(account)

        assertEquals(PhotoHash.UNKNOWN, savedBase(dao).localPhotoHash)
    }

    @Test fun a_photo_removed_on_the_phone_right_after_the_pull_leaves_the_baseline_unknown_not_empty() = runTest {
        // Review 2026-11-18, R1: "no photo" as the baseline would make the removal read as no change.
        val dao = DetailFakeContactMapDao()
        var editedAfterRead = false
        val engine = newEngine(
            aliceOnProton(),
            dao,
            DetailFakeApplier(base = 1L),
            readLocalPhotoHash = {
                editedAfterRead = true
                null
            },
            readDirtyRawIds = { if (editedAfterRead) setOf(1L) else emptySet() }
        )

        engine.sync(account)

        assertEquals(PhotoHash.UNKNOWN, savedBase(dao).localPhotoHash)
    }

    @Test fun a_row_nobody_edited_records_what_the_phone_holds_a_digest_or_no_photo() = runTest {
        for (onPhone in listOf("digest-of-the-photo", null)) {
            val dao = DetailFakeContactMapDao()

            val engine = newEngine(aliceOnProton(), dao, DetailFakeApplier(base = 1L), readLocalPhotoHash = { onPhone })

            engine.sync(account)

            assertEquals(onPhone, savedBase(dao).localPhotoHash)
        }
    }

    private fun aliceOnProton() = DetailFakeApi(
        metadataPages = listOf(metaPage(meta("c1", 100L))),
        contacts = mapOf("c1" to contact("c1", 100L, "BEGIN:VCARD\nVERSION:4.0\nFN:Alice\nEND:VCARD"))
    )

    private fun savedBase(dao: DetailFakeContactMapDao) =
        DecryptedContactJson.decode(dao.snapshot()["c1"]!!.lastKnownServerPayload!!)!!
}
