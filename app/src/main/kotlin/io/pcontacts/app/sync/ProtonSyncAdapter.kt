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
import io.pcontacts.app.notifications.SyncNotifier
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.http.AppVersionRejectedException
import io.pcontacts.core.proton.api.http.HumanVerificationRequiredException
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.storage.UserPreferences
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.core.sync.contacts.SyncReport
import io.pcontacts.core.sync.contacts.WriteReport
import io.pcontacts.core.sync.contacts.decrypt.DecryptUnavailableException
import kotlinx.coroutines.runBlocking
import java.io.IOException

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
 * DecryptUnavailableException and AppVersionRejectedException both
 * map to `numAuthExceptions` so the system sync framework treats them
 * as non-retryable. [SyncNotifier] posts user-visible notifications
 * on auth failures and persistent IO errors.
 */
class ProtonSyncAdapter(
    context: Context,
    autoInitialize: Boolean = true,
    internal val syncRunner: suspend (Context, ContentProviderClient, Account) -> Pair<WriteReport, SyncReport> =
        { ctx, prov, acct ->
            val writeLogger = RedactingLogger(tag = "ContactWrite", sink = io.pcontacts.app.logging.AndroidLogcatSink())
            val (writeEngine, readEngine) = SyncBootstrap.createBidirectionalEngines(ctx, prov, writeLogger)
            val wr = writeEngine.run {
                detectChanges(acct)
                push()
            }
            val rr = readEngine.sync(acct)
            wr to rr
        },
    internal val notifier: SyncNotifier = SyncNotifier(context),
    internal val userPreferences: UserPreferences = SharedPreferencesUserPreferences(context)
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
            val (writeReport, readReport) = runBlocking {
                syncRunner(this@ProtonSyncAdapter.context, provider, account)
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
                    "updated=${readReport.updated} deleted=${readReport.deleted} " +
                    "unchanged=${readReport.unchanged} failed=${readReport.failed}"
            }
            recordSuccess(readReport.failed)
        } catch (e: DecryptUnavailableException) {
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync requires re-auth: ${e.message}" }
            userPreferences.lastSyncErrorCode = SyncErrorCodes.REAUTH
            notifier.notifyReauthRequired(account)
        } catch (e: HumanVerificationRequiredException) {
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync paused — human verification required (Code 9001)" }
            userPreferences.lastSyncErrorCode = SyncErrorCodes.VERIFICATION
            notifier.notifyHumanVerification(account, e.verificationUrl)
        } catch (e: AppVersionRejectedException) {
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync stopped — app version rejected (Code ${e.protonCode}), update required" }
            userPreferences.lastSyncErrorCode = SyncErrorCodes.APP_VERSION
            notifier.notifyHumanVerification(account, null)
        } catch (e: IOException) {
            // A genuine network/transport failure — the connection really is
            // the problem (this includes cert-pinning rejections).
            syncResult.stats.numIoExceptions += 1
            logger.error(e) { "sync failed (network)" }
            userPreferences.lastSyncErrorCode = SyncErrorCodes.NETWORK
            if (syncResult.tooManyRetries) {
                notifier.notifyPersistentFailure(account, e.javaClass.simpleName)
            }
        } catch (e: Exception) {
            // Anything else (a bug, malformed data) — do NOT blame the
            // connection. The redacted throwable fingerprint is logged so
            // production failures like this are diagnosable.
            syncResult.stats.numIoExceptions += 1
            logger.error(e) { "sync failed (${e.javaClass.simpleName})" }
            userPreferences.lastSyncErrorCode = SyncErrorCodes.GENERIC
            if (syncResult.tooManyRetries) {
                notifier.notifyPersistentFailure(account, e.javaClass.simpleName)
            }
        }
    }

    private fun recordSuccess(failedContacts: Int) {
        userPreferences.lastSyncSuccessAtMillis = System.currentTimeMillis()
        userPreferences.lastSyncErrorCode = null
        userPreferences.lastSyncFailedContacts = failedContacts
    }
}
