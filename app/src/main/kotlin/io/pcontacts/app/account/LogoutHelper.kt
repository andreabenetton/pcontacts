// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import io.pcontacts.core.sync.AuthBootstrap
import io.pcontacts.core.sync.auth.LogoutResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges the AccountManager + ContactsContract Android surfaces
 * to `LogoutOrchestrator`. The Settings UI's "Sign out" action
 * calls `signOut(account)`; the orchestrator handles the rest
 * (server revoke, ContactsContract wipe, Room wipe, SecretStore
 * wipe, AccountManager wipe).
 *
 * Holds the ContentProviderClient open only for the duration of
 * the call so we don't leak the provider connection.
 */
class LogoutHelper(private val context: Context) {

    suspend fun signOut(account: Account): LogoutResult = withContext(Dispatchers.IO) {
        require(account.type == PROTON_ACCOUNT_TYPE) {
            "LogoutHelper only handles accounts of type $PROTON_ACCOUNT_TYPE"
        }
        val resolver = context.contentResolver
        val provider = resolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
            ?: error("ContactsProvider unavailable")
        try {
            cancelAutoSync(account)
            val orchestrator = AuthBootstrap.createLogoutOrchestrator(
                context = context,
                provider = provider,
                removeAndroidAccount = ::removeAccountBlocking
            )
            orchestrator.logout(account)
        } finally {
            // releaseUnstableContentProviderClient is API 24+; the unstable
            // variant doesn't kill our process if the provider dies, which
            // matters for the SyncAdapter's worker.
            @Suppress("DEPRECATION")
            provider.release()
        }
    }

    /**
     * AccountManager.removeAccountExplicitly is synchronous and returns
     * true on success. Wrapped here so the orchestrator's lambda type
     * (`suspend (Account) -> Boolean`) is satisfied via `withContext`
     * for thread-correctness.
     */
    private suspend fun removeAccountBlocking(account: Account): Boolean =
        withContext(Dispatchers.IO) {
            AccountManager.get(context).removeAccountExplicitly(account)
        }

    private fun cancelAutoSync(account: Account) {
        // After logout we may briefly leave a phantom Account if removal
        // races with a periodic sync trigger; tell the sync framework to
        // forget about it explicitly.
        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 0)
        ContentResolver.cancelSync(account, ContactsContract.AUTHORITY)
    }
}
