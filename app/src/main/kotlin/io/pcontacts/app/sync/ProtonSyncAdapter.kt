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
import io.pcontacts.core.sync.contacts.decrypt.DecryptUnavailableException
import kotlinx.coroutines.runBlocking

/**
 * SyncAdapter — wires the system sync framework (ADR-0004) to
 * ContactDetailSyncEngine (plan §17 task 17 wired end-to-end).
 * The body of `onPerformSync` is intentionally thin: build the engine
 * via SyncBootstrap, run it, translate the resulting SyncReport into
 * SyncResult counters.
 *
 * Idempotency lives in the engine; the SyncAdapter is allowed to fire
 * on its own schedule (plus the WorkManager belt-and-suspenders once
 * that ships) without worrying about re-doing work.
 *
 * DecryptUnavailableException maps to `numAuthExceptions` so the
 * system sync framework treats it as "needs re-auth" (which it does —
 * the user has to re-login to re-derive keyPassword) rather than a
 * retry-with-backoff IO failure.
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
        try {
            // onPerformSync runs on AbstractThreadedSyncAdapter's worker thread;
            // runBlocking parks it while the suspend bootstrap + engine complete.
            val report = runBlocking {
                val engine = SyncBootstrap.createContactDetailSyncEngine(this@ProtonSyncAdapter.context, provider)
                engine.sync(account)
            }
            syncResult.stats.numInserts += report.inserted.toLong()
            syncResult.stats.numUpdates += report.updated.toLong()
            syncResult.stats.numDeletes += report.deleted.toLong()
            logger.info {
                "sync done — server=${report.totalServer} inserted=${report.inserted} " +
                    "updated=${report.updated} deleted=${report.deleted} unchanged=${report.unchanged}"
            }
        } catch (e: DecryptUnavailableException) {
            // Stale keyPassword, missing primary key, or never-logged-in path.
            // Tell the sync framework auth is required so it stops retrying
            // until the user re-logs.
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync requires re-auth: ${e.message}" }
        } catch (t: Throwable) {
            syncResult.stats.numIoExceptions += 1
            logger.error(t) { "sync failed" }
        }
    }
}
