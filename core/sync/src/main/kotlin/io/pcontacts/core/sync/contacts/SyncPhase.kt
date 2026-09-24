// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

/**
 * The phase a sync run is in, in the order a run goes through them; a
 * phase with nothing to do is not reported. [code] is the stable value
 * persisted as `UserPreferences.syncProgressPhase` for the Settings card.
 */
enum class SyncPhase(val code: String) {
    /** Pushing the queued local changes to Proton; counted per change. */
    SENDING("sending"),

    /** Listing Proton's contacts and comparing them with the phone's; no count. */
    CHECKING("checking"),

    /** Fetching and decrypting the contacts that changed on Proton; counted per contact. */
    DOWNLOADING("downloading"),

    /** Writing the result into the Contacts provider; no count. */
    SAVING("saving")
}
