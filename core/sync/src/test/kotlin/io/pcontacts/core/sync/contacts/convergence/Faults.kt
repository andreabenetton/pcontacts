// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.convergence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.random.Random

/** Where a fault can strike: Proton calls fail like a network; the provider and Room only stop or die. */
internal enum class Site(val network: Boolean) {
    LIST(true), GET(true), CREATE(true), UPDATE(true), DELETE(true), PROVIDER(false), ROOM(false)
}

/** The process dying: nothing runs after it, not even under NonCancellable. */
internal class ProcessKilled : CancellationException("process killed")

/**
 * Seeded fault injection around every call the engines make into the fakes. A fault strikes
 * before the call commits (a network error, the system stopping the sync, the process dying)
 * or after (a lost response, a stop, a death): the boundaries between a server mutation and
 * its local acknowledgement, and between a provider write and the Room write, are all here.
 *
 * STOP cancels the run's job, as the system stopping a sync does; code under NonCancellable
 * carries on. KILL halts everything until the next run (the process restarted).
 */
internal class Faults(private val rng: Random) {
    var enabled = false
    var rate = DEFAULT_RATE

    /** The user acting while a run is in flight (opt-in): called at random checkpoints. */
    var midRun: (() -> Unit)? = null

    /** A scripted fault, by call label and side ("before"/"after"): wins over the random draw. */
    var forced: ((label: String, before: Boolean) -> String?)? = null
    private var killed = false
    private var runJob: Job? = null

    /** What struck in the current run, for the invariants. */
    val struck = mutableListOf<String>()

    /** Whether the latest contact listing of the run failed: what a deletion must not follow. */
    var listingFailed = false
        private set
    val stopped get() = struck.any { it.startsWith("STOP") || it.startsWith("KILL") }
    val injected: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
    val trace = ArrayDeque<String>()

    fun startRun(job: Job) {
        killed = false
        runJob = job
        struck.clear()
        listingFailed = false
    }

    /** The process comes back: what happens between runs is the user's doing, never faulted. */
    fun endRun() {
        killed = false
        runJob = null
    }

    fun note(event: String) {
        trace.addLast(event)
        if (trace.size > TRACE_SIZE) trace.removeFirst()
    }

    suspend fun <T> checkpoint(site: Site, label: String, commit: suspend () -> T): T {
        if (killed) throw ProcessKilled()
        if (enabled && rng.nextDouble() < YIELD_RATE) yield()
        // Not inside a batch: the pull reads the dirty rows right before one, and the few
        // milliseconds to the write are the window it cannot close (ADR-0017, 2026-10-15).
        if (enabled && !label.startsWith("apply") && rng.nextDouble() < MID_RUN_RATE) midRun?.invoke()
        strike(site, before = true, label)
        // A call that was waiting when the process died never runs.
        if (killed) throw ProcessKilled()
        val result = commit()
        note("ok $label")
        if (label.startsWith("list")) listingFailed = false
        strike(site, before = false, label)
        return result
    }

    private suspend fun strike(site: Site, before: Boolean, label: String) {
        val fault = forced?.invoke(label, before) ?: draw(site, before) ?: return
        val event = "$fault ${if (before) "before" else "after"} $label"
        struck += event
        note(event)
        if (label.startsWith("list")) listingFailed = true
        when (fault) {
            "IO", "LOST" -> throw IOException("injected: $event").also { injected += it }
            "STOP" -> {
                runJob?.cancel()
                currentCoroutineContext().ensureActive()
            }
            else -> {
                killed = true
                throw ProcessKilled().also { injected += it }
            }
        }
    }

    private fun draw(site: Site, before: Boolean): String? {
        if (!enabled || rng.nextDouble() >= rate) return null
        val choices = when {
            site.network && before -> listOf("IO", "STOP", "KILL")
            site.network -> listOf("LOST", "STOP", "KILL")
            else -> listOf("STOP", "KILL")
        }
        return choices[rng.nextInt(choices.size)]
    }

    /** Whether [t] is, or was caused by, a fault this harness injected. */
    fun isInjected(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(CAUSE_DEPTH).any { it in injected }

    private companion object {
        const val DEFAULT_RATE = 0.05
        const val YIELD_RATE = 0.2
        const val MID_RUN_RATE = 0.02
        const val TRACE_SIZE = 200
        const val CAUSE_DEPTH = 8
    }
}
