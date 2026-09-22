// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.advisories

import android.content.Context
import io.pcontacts.app.notifications.SyncNotifier
import io.pcontacts.app.settings.DependencyAuditAsset
import io.pcontacts.core.advisories.AdvisoryCheck
import io.pcontacts.core.advisories.AdvisoryResult
import io.pcontacts.core.advisories.ArtifactRef
import io.pcontacts.core.advisories.OsvClient
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.storage.UserPreferences
import io.pcontacts.feature.settings.AdvisoryCheckState
import io.pcontacts.feature.settings.DependencyAudit
import io.pcontacts.feature.settings.RuntimeAdvisory

/**
 * Wires the opt-in runtime advisory check (ADR-0025): the switch, the cached
 * state the screens show, and one check run against osv.dev for exactly the
 * artifacts of the shipped snapshot. New advisories are announced once.
 */
object AdvisoryBootstrap {

    fun state(
        context: Context,
        preferences: UserPreferences = SharedPreferencesUserPreferences(context)
    ): AdvisoryCheckState {
        if (!preferences.advisoryCheckEnabled) return AdvisoryCheckState.OFF
        val cached = preferences.advisoryResultJson?.let(AdvisoryResult::decode)
        return AdvisoryCheckState(
            enabled = true,
            lastCheckedAtMillis = preferences.lastAdvisoryCheckAtMillis,
            advisories = cached?.advisories?.map { it.toRuntime(mutedKeys(preferences)) }.orEmpty()
        )
    }

    /** The user's own assessment of a runtime advisory; keyed with the artifact version (see [RuntimeAdvisory.key]). */
    fun setMuted(
        context: Context,
        advisory: RuntimeAdvisory,
        muted: Boolean,
        preferences: UserPreferences = SharedPreferencesUserPreferences(context)
    ): AdvisoryCheckState {
        val keys = mutedKeys(preferences)
        preferences.advisoryMutedKeys = (if (muted) keys + advisory.key else keys - advisory.key).joinToString(",")
        return state(context, preferences)
    }

    private fun mutedKeys(preferences: UserPreferences): Set<String> =
        preferences.advisoryMutedKeys.split(',').filter { it.isNotEmpty() }.toSet()

    /** Off means off: the periodic work is cancelled and the cached result dropped. */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
        preferences: UserPreferences = SharedPreferencesUserPreferences(context)
    ) {
        preferences.advisoryCheckEnabled = enabled
        if (enabled) {
            AdvisoryScheduler.schedule(context)
        } else {
            AdvisoryScheduler.cancel(context)
            preferences.advisoryResultJson = null
            preferences.lastAdvisoryCheckAtMillis = 0L
            preferences.advisoryNotifiedIds = ""
            preferences.advisoryMutedKeys = ""
        }
    }

    /**
     * One check: the snapshot's artifacts and known ids go to the comparison, the result is
     * cached, and ids not announced before are announced. Throws on a network failure so the
     * caller (worker or screen) decides what to do; the cached state stays as it was.
     */
    suspend fun runCheck(
        context: Context,
        preferences: UserPreferences = SharedPreferencesUserPreferences(context),
        audit: DependencyAudit = DependencyAuditAsset.load(context),
        check: suspend (List<ArtifactRef>, Set<String>) -> AdvisoryResult = { artifacts, known ->
            AdvisoryCheck(OsvClient()).run(artifacts, known, System.currentTimeMillis())
        },
        notify: (Int) -> Unit = { SyncNotifier(context).notifyNewAdvisories(it) }
    ): AdvisoryCheckState {
        val artifacts = audit.dependencies.map { ArtifactRef(it.group, it.name, it.version) }
        val result = check(artifacts, audit.knownIds)
        preferences.advisoryResultJson = result.encode()
        preferences.lastAdvisoryCheckAtMillis = result.checkedAtMillis
        // A mute is tied to an artifact version: keys for artifacts no longer shipped at that
        // version (a bump in this release) are dropped, so the advisory is looked at afresh.
        val shipped = artifacts.map { it.coordinate }.toSet()
        val muted = mutedKeys(preferences).filter { it.substringBefore('|') in shipped }.toSet()
        preferences.advisoryMutedKeys = muted.joinToString(",")
        val announced = preferences.advisoryNotifiedIds.split(',').filter { it.isNotEmpty() }.toSet()
        val fresh = result.advisories.map { it.id }.filter { it !in announced }
        if (fresh.isNotEmpty()) {
            notify(fresh.size)
            preferences.advisoryNotifiedIds = (announced + fresh).joinToString(",")
        }
        return AdvisoryCheckState(true, result.checkedAtMillis, result.advisories.map { it.toRuntime(muted) })
    }

    private fun io.pcontacts.core.advisories.Advisory.toRuntime(mutedKeys: Set<String>) =
        RuntimeAdvisory(coordinate, id, url, severity, summary, muted = "$coordinate|$id" in mutedKeys)
}
