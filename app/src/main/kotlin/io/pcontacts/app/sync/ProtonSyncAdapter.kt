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
import io.pcontacts.core.proton.api.http.HumanVerificationRequiredException
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.core.sync.contacts.decrypt.DecryptUnavailableException
import kotlinx.coroutines.runBlocking

/**
 * SyncAdapter — wires the system sync framework (ADR-0004) to the
 * bidirectional sync pipeline (ADR-0017, ADR-0018). Push-before-pull:
 * the write engine drains the outbox first, then the read engine
 * pulls server changes.
 *
 * Idempotency lives in the engines; the SyncAdapter is allowed to fire
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
            // runBlocking parks it while the suspend bootstrap + engines complete.
            val (writeReport, readReport) = runBlocking {
                val (writeEngine, readEngine) = SyncBootstrap.createBidirectionalEngines(
                    this@ProtonSyncAdapter.context, provider
                )
                // Push-before-pull per ADR-0017 §7B.
                val wr = writeEngine.run {
                    detectChanges(account)
                    push()
                }
                val rr = readEngine.sync(account)
                wr to rr
            }
            syncResult.stats.numInserts += readReport.inserted.toLong()
            syncResult.stats.numUpdates += (readReport.updated + writeReport.updated).toLong()
            syncResult.stats.numDeletes += (readReport.deleted + writeReport.deleted).toLong()
            if (!writeReport.isNoOp()) {
                logger.info {
                    "push done — pushed=${writeReport.pushed} created=${writeReport.created} " +
                        "updated=${writeReport.updated} deleted=${writeReport.deleted} " +
                        "failed=${writeReport.failed} conflicted=${writeReport.conflicted}"
                }
            }
            logger.info {
                "pull done — server=${readReport.totalServer} inserted=${readReport.inserted} " +
                    "updated=${readReport.updated} deleted=${readReport.deleted} unchanged=${readReport.unchanged}"
            }
        } catch (e: DecryptUnavailableException) {
            // Stale keyPassword, missing primary key, or never-logged-in path.
            // Tell the sync framework auth is required so it stops retrying
            // until the user re-logs.
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync requires re-auth: ${e.message}" }
        } catch (e: HumanVerificationRequiredException) {
            // Proton wants the user to clear a captcha / recovery flow.
            // Stop retrying until the user completes it in the app UI.
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync paused — human verification required (Code 9001)" }
        } catch (t: Throwable) {
            syncResult.stats.numIoExceptions += 1
            logger.error(t) { "sync failed" }
        }
    }
}
