// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import android.accounts.Account
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.storage.SecretStore
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.SyncStateDao

/**
 * Per plan §5 + §17 task 19. End-to-end logout in five steps:
 *
 *   1. Server-side revoke   — DELETE /core/v4/auth so the session UID is
 *                             invalidated and the access/refresh tokens
 *                             can no longer be used.
 *   2. Delete RawContacts   — Remove every Data row our account owns
 *                             from the system Contacts app.
 *   3. Clear Room mapping   — Wipe `contact_map` + the per-account
 *                             `sync_state` row so a future sign-in
 *                             starts from a clean slate.
 *   4. SecretStore.logout() — Zero every persisted secret (UID,
 *                             AccessToken, RefreshToken, keyPassword)
 *                             and delete the Keystore AEAD KEK
 *                             (ADR-0009).
 *   5. AccountManager wipe  — Remove the Android `Account` so the
 *                             system Settings → Accounts screen stops
 *                             showing it.
 *
 * Best-effort: a failure in steps 1–3 logs (non-sensitive) and
 * continues, because the user has chosen to sign out and the local
 * cleanup MUST happen even if the server is unreachable. Step 4 is
 * the only step that must succeed for the device to be "safe to
 * hand to someone else"; step 5 is the UX bookkeeping.
 *
 * Per CLAUDE.md anti-patterns the AccountManager call lives in :app
 * (the only module that depends on AccountManager); we take it as a
 * lambda.
 */
class LogoutOrchestrator(
    private val authApi: ProtonAuthApi,
    private val secretStore: SecretStore,
    private val session: InMemorySession,
    private val contactMapDao: ContactMapDao,
    private val syncStateDao: SyncStateDao,
    /** Deletes every RawContact owned by `account`; returns the row count. */
    private val deleteAllContactsFor: suspend (Account) -> Int,
    /** Removes the Android Account; returns true on success. */
    private val removeAndroidAccount: suspend (Account) -> Boolean,
    private val logger: Logger = RedactingLogger(tag = "Logout", sink = NoOpSink)
) {

    suspend fun logout(account: Account): LogoutResult {
        val errors = ArrayList<String>(5)

        // 1) Server-side revoke. Failure here is non-fatal — the user
        //    expects to sign out even when offline.
        try {
            authApi.revoke()
        } catch (t: Throwable) {
            logger.warn(t) { "server-side revoke failed; continuing with local wipe" }
            errors += LOGOUT_ERR_REVOKE
        }

        // 2) Delete RawContacts.
        val contactsDeleted = try {
            deleteAllContactsFor(account)
        } catch (t: Throwable) {
            logger.error(t) { "delete RawContacts failed" }
            errors += LOGOUT_ERR_CONTACTS
            0
        }

        // 3) Clear Room mapping + per-account sync state.
        try {
            contactMapDao.deleteAll()
            syncStateDao.delete(account.name)
        } catch (t: Throwable) {
            logger.error(t) { "clear Room mapping failed" }
            errors += LOGOUT_ERR_ROOM
        }

        // 4) SecretStore wipe + Keystore alias deletion + session clear.
        try {
            secretStore.logout()
        } catch (t: Throwable) {
            logger.error(t) { "SecretStore.logout() failed" }
            errors += LOGOUT_ERR_SECRETSTORE
        }
        session.clear()

        // 5) Remove the Android Account so the system Settings screen
        //    reflects the signed-out state.
        val androidAccountRemoved = try {
            removeAndroidAccount(account)
        } catch (t: Throwable) {
            logger.error(t) { "AccountManager.removeAccountExplicitly failed" }
            errors += LOGOUT_ERR_ACCOUNT_MANAGER
            false
        }

        logger.info {
            "logout done — contactsDeleted=$contactsDeleted " +
                "accountRemoved=$androidAccountRemoved errors=${errors.size}"
        }
        return LogoutResult(
            contactsDeleted = contactsDeleted,
            androidAccountRemoved = androidAccountRemoved,
            errors = errors.toList()
        )
    }

    companion object {
        const val LOGOUT_ERR_REVOKE = "revoke_failed"
        const val LOGOUT_ERR_CONTACTS = "contacts_delete_failed"
        const val LOGOUT_ERR_ROOM = "room_clear_failed"
        const val LOGOUT_ERR_SECRETSTORE = "secretstore_wipe_failed"
        const val LOGOUT_ERR_ACCOUNT_MANAGER = "account_remove_failed"
    }
}

/**
 * Summary surfaced to the UI / log. `successful` is true iff every
 * step finished without error; otherwise inspect `errors` (string
 * codes, non-sensitive) to surface the right message.
 */
data class LogoutResult(
    val contactsDeleted: Int,
    val androidAccountRemoved: Boolean,
    val errors: List<String>
) {
    val successful: Boolean get() = errors.isEmpty()
}
