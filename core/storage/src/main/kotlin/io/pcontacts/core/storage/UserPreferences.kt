// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

/**
 * Read/write surface for non-secret user preferences. Unlike
 * [SecretStore], these values are not encrypted — they hold no
 * sensitive material.
 *
 * Per CLAUDE.md all SharedPreferences access is centralised in
 * `:core:storage`; this interface is the non-secret companion to
 * [SecretStore].
 */
interface UserPreferences {

    /** Periodic sync interval in hours. Default [DEFAULT_SYNC_INTERVAL_HOURS]. */
    var syncIntervalHours: Long

    /** Whether the POST_NOTIFICATIONS runtime permission has been requested. */
    var notificationPermissionRequested: Boolean

    /** Whether READ_CONTACTS / WRITE_CONTACTS runtime permissions have been requested. */
    var contactsPermissionRequested: Boolean

    /**
     * Wall-clock millis of the last successful sync, or `0` if a sync
     * has never completed successfully. Recorded by the sync adapter so
     * "last sync" reflects the sync event itself — not whether any
     * contact happened to be stored (an empty or all-failed account
     * would otherwise read "never" forever).
     */
    var lastSyncSuccessAtMillis: Long

    /**
     * Wall-clock millis of the last sync run that completed, whatever
     * it achieved. Differs from [lastSyncSuccessAtMillis], which is
     * stamped only when the run left nothing failed, pending,
     * conflicted or quarantined ("fully converged").
     */
    var lastSyncRunAtMillis: Long

    /**
     * Stable, non-sensitive code for the most recent sync failure
     * (e.g. `reauth`, `verification`, `app_version`, `io`), or `null`
     * when the last sync attempt succeeded. Lets the UI distinguish
     * "never synced" from "sync is failing".
     */
    var lastSyncErrorCode: String?

    /**
     * Number of contacts skipped by the most recent sync because they
     * failed to fetch/decrypt/parse. `0` when the last sync had no such
     * failures. Lets the launcher report partial-success ("N contacts
     * couldn't be synced") without persisting any contact content.
     */
    var lastSyncFailedContacts: Int

    /**
     * Progress of the sync run in flight: the phase it is in (a stable,
     * non-sensitive code the sync engines define; `null` before the first
     * phase and when no run is active) and that phase's items done so far
     * out of its total (both `0` for a phase without a count). Written by
     * the engines, cleared by the sync adapter when the run ends; the
     * Settings card polls it while a sync is running.
     */
    var syncProgressPhase: String?
    var syncProgressDone: Int
    var syncProgressTotal: Int

    /**
     * How many sync problems (failed contacts, failed or quarantined changes, conflicts) the
     * last background-sync notification announced; 0 when none is showing. A background run
     * notifies only when the number changes, and a clean run clears it.
     */
    var syncProblemsNotified: Int

    /** Whether the user acknowledged the OS-apps contact-access notice ("Got it"). */
    var systemContactsNoticeDismissed: Boolean

    /**
     * `true` from the moment the pre-2.0 secret file was purged (the
     * session it held cannot be carried into the Keystore-sealed store,
     * ADR-0009) until the user has signed in again. The app signs the
     * stale account out on the next start and the sign-in screen
     * explains why. Survives [clearSyncState] (that sign-out runs it);
     * cleared by the sign-in that completes afterwards.
     */
    var secretsStorageUpgraded: Boolean

    /**
     * The versionCode whose open-vulnerability notice (ADR-0024) has been
     * posted; 0 when none. One notification per shipped version, not per start.
     */
    var vulnerabilityNoticeVersionCode: Int

    /** ADR-0025: the opt-in runtime advisory check against osv.dev. Off by default. */
    var advisoryCheckEnabled: Boolean

    /** When the last runtime advisory check completed; 0 when never. */
    var lastAdvisoryCheckAtMillis: Long

    /** The last runtime result, as the JSON `:core:advisories` writes; null when none. Public data. */
    var advisoryResultJson: String?

    /** Advisory ids already announced by a notification, comma-separated. */
    var advisoryNotifiedIds: String

    /**
     * Runtime advisories the user muted, as `coordinate|id` keys, comma-separated. The
     * coordinate carries the artifact version, so a bump makes the key stale; stale keys
     * are pruned on every check.
     */
    var advisoryMutedKeys: String

    /**
     * Forgets everything that describes the signed-out account's syncs:
     * last success time, last error, failed-contact count and any
     * in-flight progress. Device preferences (interval, permission and
     * notice flags) and [secretsStorageUpgraded] are kept — they are
     * about this install, not the account. Called by the sign-out flow.
     */
    fun clearSyncState()

    companion object {
        const val DEFAULT_SYNC_INTERVAL_HOURS = 12L
        val ALLOWED_INTERVALS_HOURS = listOf(1L, 3L, 6L, 12L, 24L)
    }
}
