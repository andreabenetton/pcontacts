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
     * Progress of the sync run in flight: contacts processed so far and
     * the server total, both `0` when no run is active. Written by the
     * engine every few contacts, cleared by the sync adapter when the
     * run ends; the Settings card polls it while a sync is running.
     */
    var syncProgressDone: Int
    var syncProgressTotal: Int

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
     * Forgets everything that describes the signed-out account's syncs:
     * last success time, last error, failed-contact count and any
     * in-flight progress. Device preferences (interval, permission and
     * notice flags) and [secretsStorageUpgraded] are kept — they are
     * about this install, not the account. Called by the sign-out flow.
     */
    fun clearSyncState()

    companion object {
        const val DEFAULT_SYNC_INTERVAL_HOURS = 12L
        val ALLOWED_INTERVALS_HOURS = listOf(1L, 6L, 12L, 24L)
    }
}
