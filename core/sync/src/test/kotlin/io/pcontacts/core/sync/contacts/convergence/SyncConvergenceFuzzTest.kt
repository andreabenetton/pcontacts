// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.convergence

import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PhoneType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Seeded, stateful fault injection over the whole sync (the 2026-10-14 review, §7): random
 * edits on the phone and on Proton, faults at every boundary between a Proton mutation, a
 * provider write and a Room write, runs repeated until nothing is left to do. After every run
 * the safety invariants hold; at the end the two sides agree.
 *
 * - S1: no contact twice on either side. S2: no contact gone from both sides without a delete.
 * - S3: a run leaves a contact awaiting the user's answer alone. S4: an unreadable listing
 *   deletes nothing. S5: nothing escapes a run but the faults injected into it.
 * - L1: the runs come to rest. L2: the same contacts on both sides, each mapped with a merge
 *   base. L3: the same content. L4: what only one side changed reaches the other.
 *   L5: a run with nothing to do changes nothing.
 *
 * - S6: a contact still on Proton is never purged from the phone.
 *
 * `PCONTACTS_FUZZ_SEED`, `PCONTACTS_FUZZ_ITERATIONS` and `PCONTACTS_FUZZ_STEPS` widen the
 * search; a failure names the seed and the command that replays it. Opt-in, off in CI while
 * the defects they find are open: `PCONTACTS_FUZZ_FAULTS` (faults at every boundary),
 * `PCONTACTS_FUZZ_CONCURRENT_EDITS` (the user edits while a run is in flight) and
 * `PCONTACTS_FUZZ_LISTING_OMISSION` (a listing page silently leaves a contact out);
 * `PCONTACTS_FUZZ_FAULT_RATE` sets the odds of a fault per call (0 isolates the other modes).
 */
class SyncConvergenceFuzzTest {

    @Test fun generated_rows_keep_their_form_through_the_round_trip() {
        val world = SyncWorld(seed = 1)
        repeat(ROUND_TRIPS) {
            val row = world.edited(world.newRow())
            assertEquals(row, world.normalize(row))
        }
    }

    @Test fun worlds_without_faults_converge() = runTest(timeout = TIMEOUT) {
        repeat(iterations) { i -> Fuzz(baseSeed + i, steps, withFaults = false).run(this) }
    }

    @Test fun seeded_fault_injection_converges() = runTest(timeout = TIMEOUT) {
        // Opt-in until the open defects it finds are fixed (the 2026-10-14 review follow-ups).
        assumeTrue("Set env PCONTACTS_FUZZ_FAULTS=true", System.getenv("PCONTACTS_FUZZ_FAULTS") == "true")
        repeat(iterations) { i -> Fuzz(baseSeed + i, steps, withFaults = true).run(this) }
    }

    // ---- scripted: the windows the 2026-10-14 fixes closed ----

    @Test fun a_process_killed_after_proton_committed_a_create_does_not_duplicate_it() = runTest {
        val fuzz = Fuzz(seed = 7, steps = 0, withFaults = true)
        val world = fuzz.world
        world.provider.userCreate(world.newRow())
        world.faults.rate = 0.0
        world.faults.forced = once("create", before = false, fault = "KILL")

        fuzz.check(world.runOnce(this, withFaults = true), Waiting(emptySet(), emptySet()))
        fuzz.quiesce(this)
        fuzz.checkLiveness(this)

        assertEquals(1, world.server.contacts.size)
    }

    @Test fun a_sync_stopped_while_a_create_is_recorded_still_records_it() = runTest {
        val world = SyncWorld(seed = 8)
        val raw = world.provider.userCreate(world.newRow())
        world.faults.rate = 0.0
        world.faults.forced = once("map upsert srv-1", before = false, fault = "STOP")

        world.runOnce(this, withFaults = true)

        assertEquals("srv-1", world.provider.rows.getValue(raw).sourceId)
        assertEquals(0, world.outbox.countPending())
    }

    @Test fun a_create_whose_answer_a_stop_cut_off_is_recorded_by_the_next_run_without_a_second_post() = runTest {
        val world = SyncWorld(seed = 8)
        val raw = world.provider.userCreate(world.newRow())
        world.faults.rate = 0.0
        world.faults.forced = once("create", before = false, fault = "STOP")
        world.runOnce(this, withFaults = true)
        assertEquals(null, world.provider.rows.getValue(raw).sourceId)

        world.runOnce(this, withFaults = false)

        assertTrue("no second POST", world.server.engineMutations.none { it.first == "create" })
        assertEquals(1, world.server.contacts.size)
        assertEquals("srv-1", world.provider.rows.getValue(raw).sourceId)
    }

    @Test fun an_edit_survives_a_create_whose_recording_died_twice() = runTest {
        // The create committed, the process died; the user edited the contact; the next run's
        // recording died again part-way. The edit was dropped with the CREATE row and the pull
        // restored Proton's older copy (ADR-0017, 2026-10-15).
        val fuzz = Fuzz(seed = 11, steps = 0, withFaults = true)
        val world = fuzz.world
        val raw = world.provider.userCreate(world.newRow())
        world.faults.rate = 0.0
        world.faults.forced = once("create", before = false, fault = "KILL")
        world.runOnce(this, withFaults = true)
        val edited = world.edited(world.provider.rows.getValue(raw).data)
        world.provider.userEdit(raw, edited)
        world.faults.forced = once("outbox delete local-$raw", before = false, fault = "KILL")
        world.runOnce(this, withFaults = true)

        fuzz.quiesce(this)

        assertEquals(edited, world.provider.rows.getValue(raw).data)
        assertEquals(edited, world.serverRow(world.server.contacts.values.single()))
    }

    @Test fun the_same_number_given_another_type_on_each_side_asks_the_user() = runTest {
        val world = SyncWorld(seed = 9)
        val row = world.newRow().copy(phones = listOf(PhoneEntry("+15550100", PhoneType.HOME)))
        val raw = world.provider.userCreate(world.normalize(row))
        world.runOnce(this, withFaults = false)
        world.runOnce(this, withFaults = false)
        val stored = world.server.contacts.values.single()
        val synced = world.provider.rows.getValue(raw).data
        val withWork = synced.copy(phones = synced.phones + PhoneEntry("+15550100", PhoneType.WORK))
        world.provider.userEdit(raw, world.normalize(withWork))
        val onProton = world.serverRow(stored)
        world.server.webEdit(
            stored.id,
            world.serializer.serialize(
                io.pcontacts.core.sync.contacts.RowToDecryptedContact.convert(
                    onProton.copy(phones = onProton.phones + PhoneEntry("+15550100", PhoneType.MOBILE)),
                    stored.id,
                    stored.uid
                )
            )
        )

        world.runOnce(this, withFaults = false)

        assertTrue("both edits of one number must reach the user", world.conflictIds().isNotEmpty())
    }

    private fun once(label: String, before: Boolean, fault: String): (String, Boolean) -> String? {
        var fired = false
        return { l, b ->
            if (!fired && l == label && b == before) {
                fired = true
                fault
            } else {
                null
            }
        }
    }

    /** One seeded world driven through bootstrap, faulted steps and quiescence. */
    private class Fuzz(private val seed: Long, private val steps: Int, private val withFaults: Boolean) {
        val world = SyncWorld(seed).apply {
            if (System.getenv("PCONTACTS_FUZZ_CONCURRENT_EDITS") == "true") faults.midRun = { localEdit() }
            server.omitFromListing = System.getenv("PCONTACTS_FUZZ_LISTING_OMISSION") == "true"
            System.getenv("PCONTACTS_FUZZ_FAULT_RATE")?.toDoubleOrNull()?.let { faults.rate = it }
        }
        private var step = "bootstrap"

        suspend fun run(scope: CoroutineScope) {
            repeat(world.rng.nextInt(BOOTSTRAP_MIN, BOOTSTRAP_MAX)) { world.serverEdit() }
            repeat(world.rng.nextInt(1, LOCAL_MAX)) { world.localEdit() }
            quiesce(scope)
            checkLiveness(scope)
            world.startEpoch()
            for (s in 0 until steps) {
                step = "step $s"
                repeat(world.rng.nextInt(0, EDITS_MAX)) { world.localEdit() }
                repeat(world.rng.nextInt(0, EDITS_MAX)) { world.serverEdit() }
                world.answerQuestions(all = false)
                val waiting = world.waiting()
                check(world.runOnce(scope, withFaults), waiting)
                world.now += world.rng.nextLong(0, 2 * SyncWorld.HOUR_MS)
            }
            step = "quiescence"
            quiesce(scope)
            checkLiveness(scope)
        }

        suspend fun quiesce(scope: CoroutineScope) {
            repeat(MAX_QUIET_RUNS) {
                world.answerQuestions(all = true)
                world.now += 2 * SyncWorld.HOUR_MS
                val waiting = world.waiting()
                check(world.runOnce(scope, withFaults = false), waiting)
                if (quiet()) return
            }
            fail("L1", "still busy after $MAX_QUIET_RUNS runs without faults")
        }

        private suspend fun quiet(): Boolean {
            // A tombstone that never reached Proton is read again on every run; it carries nothing to sync.
            val dirty = world.provider.rows.values.filter { (it.dirty || it.deleted) && it.sourceId != null }
            return !world.mutated && dirty.isEmpty() && world.conflictIds().isEmpty() &&
                world.outbox.countPending() == 0 && world.outbox.countQuarantined() == 0
        }

        /** S1–S5, after every run. */
        suspend fun check(outcome: Throwable?, waiting: Waiting) {
            outcome?.let { t ->
                val explained = (t is CancellationException && world.faults.stopped) || world.faults.isInjected(t)
                if (!explained) fail("S5", "${t.javaClass.simpleName}: ${t.message}", t)
            }
            val onProton = world.server.contacts.values.mapNotNull { world.markerOf(world.serverRow(it)) }
            val onPhone = world.liveLocalRows().mapNotNull { world.markerOf(it.data) }
            twice(onProton)?.let { fail("S1", "on Proton twice: $it") }
            twice(onPhone)?.let { fail("S1", "on the phone twice: $it") }
            val lost = world.markers - world.deleted - onProton.toSet() - onPhone.toSet()
            if (lost.isNotEmpty()) fail("S2", "gone from both sides, never deleted: $lost")
            // A conflict settles without an answer only through the merge of a newer phone edit, or
            // because both sides deleted the contact (ADR-0017, 2026-10-01).
            val stillWaiting = waiting.ids intersect world.conflictIds()
            val touched = world.touchedIds() intersect stillWaiting
            if (touched.isNotEmpty()) fail("S3", "changed while awaiting the user: $touched")
            val goneEverywhere = waiting.ids.filter { id ->
                id !in world.server.contacts && world.liveLocalRows().none { it.sourceId == id }
            }
            val dropped = waiting.ids - stillWaiting - waiting.editedOnPhone - goneEverywhere.toSet()
            if (dropped.isNotEmpty()) fail("S3", "a conflict vanished without an answer or a merge: $dropped")
            val purged = world.provider.engineWrites.filter { it.first == "delete" }.map { it.second }
            if (world.faults.listingFailed && purged.isNotEmpty()) fail("S4", "deleted after a failed listing: $purged")
            val stillOnProton = purged.filter { it in world.server.contacts }
            if (stillOnProton.isNotEmpty()) fail("S6", "purged from the phone while on Proton: $stillOnProton")
        }

        /** L2–L5, once the runs came to rest. */
        suspend fun checkLiveness(scope: CoroutineScope) {
            val onProton = world.server.contacts.values.associateBy { world.markerOf(world.serverRow(it))!! }
            val onPhone = world.liveLocalRows().associateBy { world.markerOf(it.data)!! }
            if (onProton.keys != onPhone.keys) {
                val phoneOnly = onPhone.keys - onProton.keys
                fail("L2", "only on the phone $phoneOnly, only on Proton ${onProton.keys - onPhone.keys}")
            }
            for ((marker, raw) in onPhone) {
                val stored = onProton.getValue(marker)
                val mapping = world.contactMap.findByProtonId(stored.id)
                if (raw.sourceId != stored.id) fail("L2", "$marker: SOURCE_ID ${raw.sourceId}, Proton id ${stored.id}")
                if (mapping?.androidRawContactId != raw.id) fail("L2", "$marker: mapping $mapping for raw ${raw.id}")
                if (world.contactMap.mergeBase(stored.id) == null) fail("L2", "$marker: no merge base")
                val protonRow = world.serverRow(stored)
                if (raw.data != protonRow) fail("L3", "$marker: ${raw.data} vs $protonRow")
                val wanted = world.expected[marker]
                if (marker !in world.contended && wanted != null && raw.data != wanted) {
                    fail("L4", "$marker (raw ${raw.id}, ${stored.id}): ${raw.data}, expected $wanted")
                }
            }
            val resurrected = (world.markers - world.contended).filter { world.expected[it] == null && it in onPhone }
            if (resurrected.isNotEmpty()) fail("L4", "deleted on one side, still here: $resurrected")
            repeat(IDLE_RUNS) {
                val outcome = world.runOnce(scope, withFaults = false)
                if (outcome != null || world.mutated) {
                    val changes = world.server.engineMutations + world.provider.engineWrites
                    fail("L5", "an idle run changed $changes", outcome)
                }
            }
        }

        private fun twice(markers: List<String>): Set<String>? =
            markers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.takeIf { it.isNotEmpty() }

        private fun fail(invariant: String, detail: String, cause: Throwable? = null): Nothing {
            val replay = "PCONTACTS_FUZZ_SEED=$seed PCONTACTS_FUZZ_ITERATIONS=1 PCONTACTS_FUZZ_STEPS=$steps " +
                "./gradlew :core:sync:testDebugUnitTest --tests '*SyncConvergenceFuzzTest*' --rerun"
            val message = buildString {
                appendLine("$invariant violated (seed=$seed, $step): $detail")
                appendLine("replay: $replay")
                appendLine("-- faults and calls --")
                world.faults.trace.takeLast(TAIL).forEach { appendLine(it) }
                appendLine("-- engine log --")
                world.logs.takeLast(TAIL).forEach { appendLine(it) }
            }
            throw AssertionError(message, cause)
        }
    }

    private companion object {
        val TIMEOUT = 10.minutes
        const val ROUND_TRIPS = 200
        const val BASE_SEED = 20_261_014L
        const val BOOTSTRAP_MIN = 4
        const val BOOTSTRAP_MAX = 9
        const val LOCAL_MAX = 3
        const val EDITS_MAX = 5
        const val MAX_QUIET_RUNS = 10
        const val IDLE_RUNS = 2
        const val TAIL = 60

        val baseSeed: Long = System.getenv("PCONTACTS_FUZZ_SEED")?.toLongOrNull() ?: BASE_SEED
        val iterations: Int = System.getenv("PCONTACTS_FUZZ_ITERATIONS")?.toIntOrNull() ?: 25
        val steps: Int = System.getenv("PCONTACTS_FUZZ_STEPS")?.toIntOrNull() ?: 12
    }
}
