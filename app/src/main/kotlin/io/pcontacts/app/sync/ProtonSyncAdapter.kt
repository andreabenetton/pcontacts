// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import io.pcontacts.app.logging.AndroidLogcatSink
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.sync.contacts.SyncBootstrap
import kotlinx.coroutines.runBlocking

/**
 * SyncAdapter — wires the system sync framework (ADR-0004) to the
 * EmailSyncEngine (plan §17 task 16). The body of `onPerformSync` is
 * intentionally thin: build the engine via SyncBootstrap, run it,
 * translate the resulting SyncReport into SyncResult counters.
 *
 * Idempotency lives in EmailSyncEngine; the SyncAdapter is allowed to
 * fire on its own schedule (plus the WorkManager belt-and-suspenders
 * once that ships) without worrying about re-doing work.
 */
class ProtonSyncAdapter(
    context: Context,
    autoInitialize: Boolean = true
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    private val logger: Logger = RedactingLogger(tag = "ProtonSync", sink = AndroidLogcatSink())

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        logger.info { "sync start account=${account.name} authority=$authority" }
        val engine = SyncBootstrap.createEmailSyncEngine(this.context, provider)

        try {
            // onPerformSync runs on AbstractThreadedSyncAdapter's worker thread;
            // runBlocking parks it while the suspend engine completes.
            val report = runBlocking { engine.sync(account) }
            syncResult.stats.numInserts += report.inserted.toLong()
            syncResult.stats.numUpdates += report.updated.toLong()
            syncResult.stats.numDeletes += report.deleted.toLong()
            logger.info {
                "sync done — server=${report.totalServer} inserted=${report.inserted} " +
                    "updated=${report.updated} deleted=${report.deleted} unchanged=${report.unchanged}"
            }
        } catch (t: Throwable) {
            syncResult.stats.numIoExceptions += 1
            logger.error(t) { "sync failed" }
        }
    }
}
