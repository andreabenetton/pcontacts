// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.contacts.ContactsAccountSettings
import io.pcontacts.core.logging.Logger

/**
 * Finalizes a successful login: registers the Android [Account], marks
 * the contacts authority syncable, ensures the Contacts Provider
 * settings row (ungrouped contacts visible — see
 * [ContactsAccountSettings]), and requests the first sync so a fresh
 * account doesn't sit empty waiting for the scheduler.
 *
 * The ordering is deliberate: the settings row exists before the first
 * contact is written, and the sync request is issued before the caller
 * reports the authenticator result.
 */
internal object ProtonAccountRegistrar {

    fun register(
        context: Context,
        uid: String,
        username: String,
        logger: Logger? = null
    ): Account {
        val accountManager = AccountManager.get(context)
        val account = Account(username, PROTON_ACCOUNT_TYPE)
        // No password, no initial userdata — tokens live in SecretStore.
        val added = accountManager.addAccountExplicitly(account, null, null)
        if (!added) {
            // Re-login into an existing account: keep it (and every
            // RawContacts row tied to it) and just refresh its state.
            logger?.info { "account already registered — refreshing state" }
        }
        accountManager.setUserData(account, "proton_uid", uid)

        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)

        val settingsReady = ContactsAccountSettings.ensureVisibleAndSyncable(
            context.contentResolver,
            account,
            logger
        )
        if (!settingsReady) {
            logger?.warn { "contacts Settings row not initialized after login; next sync retries" }
        }

        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
        return account
    }
}
