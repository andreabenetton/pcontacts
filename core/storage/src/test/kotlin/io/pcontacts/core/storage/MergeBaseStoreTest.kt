// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.storage.db.DatabaseFactory
import io.pcontacts.core.storage.db.PcontactsDatabase
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MergeBaseStoreTest {

    private lateinit var db: PcontactsDatabase

    /** Reverses the bytes: enough to prove the column never holds the plaintext. */
    private val cipher = object : SecretCipher {
        var failUnwrap = false
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.reversedArray()
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (failUnwrap) throw AEADBadTagException("tag mismatch")
            return wrapped.reversedArray()
        }
        override fun delete() = Unit
    }

    @Before fun setUp() = runTest {
        db = DatabaseFactory.createInMemory(ApplicationProvider.getApplicationContext<Context>())
        db.contactMapDao().upsert(
            ContactMapEntity(
                protonContactId = "ct-1",
                protonUid = null,
                androidRawContactId = 1L,
                modifyTime = 0L,
                contentHash = "",
                isVerified = true,
                deleted = false,
                syncStatus = ContactMapEntity.Status.CLEAN,
                lastError = null,
                lastSyncedAt = 0L
            )
        )
    }

    @After fun tearDown() = db.close()

    private fun store() = KeystoreMergeBaseStore(
        db.contactMapDao(),
        cipher,
        RedactingLogger(tag = "t", sink = NoOpSink)
    )

    @Test fun seals_before_the_row_is_written_and_opens_on_read() = runTest {
        val plaintext = "{\"v\":1}".encodeToByteArray()

        store().save("ct-1", plaintext)

        val stored = db.contactMapDao().mergeBase("ct-1")!!
        assertFalse(stored.contentEquals(plaintext))
        assertTrue(plaintext.contentEquals(store().load("ct-1")))
    }

    @Test fun unreadable_blob_loads_as_null() = runTest {
        store().save("ct-1", byteArrayOf(1, 2, 3))
        cipher.failUnwrap = true

        assertNull(store().load("ct-1"))
    }

    @Test fun missing_row_and_missing_base_load_as_null() = runTest {
        assertNull(store().load("ct-1"))
        assertNull(store().load("ct-unknown"))
    }

    @Test fun in_memory_store_round_trips_copies() = runTest {
        val memory = InMemoryMergeBaseStore()
        val plaintext = byteArrayOf(4, 5)

        memory.save("ct-1", plaintext)
        plaintext[0] = 9

        assertTrue(byteArrayOf(4, 5).contentEquals(memory.load("ct-1")))
        memory.remove("ct-1")
        assertNull(memory.load("ct-1"))
    }
}
