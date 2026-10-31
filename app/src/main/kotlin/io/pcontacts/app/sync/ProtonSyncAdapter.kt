// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import io.pcontacts.app.contacts.ContactsAccountSettings
import io.pcontacts.app.logging.AndroidLogcatSink
import io.pcontacts.app.notifications.SyncNotifier
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.http.AppVersionRejectedException
import io.pcontacts.core.proton.api.http.HumanVerificationRequiredException
import io.pcontacts.core.proton.api.http.ProtonApiException
import io.pcontacts.core.proton.api.http.SessionRevokedException
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.storage.UserPreferences
import io.pcontacts.core.sync.contacts.SyncBootstrap
import io.pcontacts.core.sync.contacts.SyncReport
import io.pcontacts.core.sync.contacts.WriteReport
import io.pcontacts.core.sync.contacts.decrypt.DecryptUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException

private const val MILLIS_PER_SECOND = 1000L

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
                push(acct)
            }
            val rr = readEngine.sync(acct)
            wr to rr
        },
    internal val notifier: SyncNotifier = SyncNotifier(context),
    internal val userPreferences: UserPreferences = SharedPreferencesUserPreferences(context),
    internal val settingsInitializer: (ContentResolver, Account, Logger?) -> Boolean =
        ContactsAccountSettings::ensureVisibleAndSyncable
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    private val logger: Logger = RedactingLogger(tag = "ProtonSync", sink = AndroidLogcatSink())

    /**
     * Set by [onSyncCanceled]: the system stopped this run (a job time limit, a newer request).
     * Whatever the interrupted call then throws — `InterruptedException`, or an
     * `InterruptedIOException` from the network stack — is that stop, not a failure (issue #70).
     */
    @Volatile private var stoppedBySystem = false

    override fun onSyncCanceled() {
        stoppedBySystem = true
        super.onSyncCanceled()
    }

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        logger.info { "sync start account=${account.name} authority=$authority" }
        stoppedBySystem = false
        val startedAt = System.currentTimeMillis()
        if (!settingsInitializer(context.contentResolver, account, logger)) {
            // Non-fatal: self-heals on the next sync (provider resets, OEM
            // providers that create their settings row late, etc.).
            logger.warn { "contacts Settings init failed — continuing sync" }
        }
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
            recordRun(writeReport, readReport)
            val manual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
            announceProblems(writeReport, readReport, manual)
        } catch (e: CancellationException) {
            // A cancelled run (the framework's onSyncCanceled) is neither a failure nor a run.
            throw e
        } catch (e: InterruptedException) {
            stoppedRun(startedAt, e)
        } catch (e: DecryptUnavailableException) {
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync requires re-auth: ${e.message}" }
            userPreferences.lastSyncErrorCode = SyncErrorCodes.REAUTH
            // A 1.x install whose secret file was just purged is asked to sign in again
            // because of the storage upgrade, not an expired session (the app signs the
            // stale account out on its next start and the sign-in screen explains it).
            notifier.notifyReauthRequired(account, storageUpgrade = userPreferences.secretsStorageUpgraded)
        } catch (e: SessionRevokedException) {
            // Before the IOException branch: a revoked session (signed out everywhere, password or
            // password mode changed on the web) needs a sign-in, not Android's retries.
            syncResult.stats.numAuthExceptions += 1
            logger.warn { "sync requires re-auth: session revoked (HTTP ${e.httpStatus})" }
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
            notifier.notifyUpdateRequired()
        } catch (e: ProtonApiException) {
            // Before the IOException branch: Proton answered and refused (a Code inside a 2xx);
            // the type is an IOException only for OkHttp's sake. Not a connection problem.
            logger.warn { "sync failed — Proton refused with Code ${e.protonCode}" }
            recordRetryableFailure(account, syncResult, SyncErrorCodes.GENERIC, "Proton code ${e.protonCode}")
        } catch (e: IOException) {
            if (stoppedBySystem) return stoppedRun(startedAt, e)
            // A genuine network/transport failure — the connection really is
            // the problem (this includes cert-pinning rejections).
            logger.error(e) { "sync failed (network)" }
            recordRetryableFailure(account, syncResult, SyncErrorCodes.NETWORK, e.javaClass.simpleName)
        } catch (e: Exception) {
            if (stoppedBySystem) return stoppedRun(startedAt, e)
            // Anything else (a bug, malformed data) — do NOT blame the
            // connection. The redacted throwable fingerprint is logged so
            // production failures like this are diagnosable.
            logger.error(e) { "sync failed (${e.javaClass.simpleName})" }
            recordRetryableFailure(account, syncResult, SyncErrorCodes.GENERIC, e.javaClass.simpleName)
        } finally {
            // The in-flight phase and "N of total" are meaningless once the run is over, however it ended.
            userPreferences.syncProgressPhase = null
            userPreferences.syncProgressDone = 0
            userPreferences.syncProgressTotal = 0
        }
    }

    /** A failed run Android may retry: an I/O failure to the framework, [code] for the Sync card. */
    private fun recordRetryableFailure(account: Account, syncResult: SyncResult, code: String, detail: String) {
        syncResult.stats.numIoExceptions += 1
        userPreferences.lastSyncErrorCode = code
        if (syncResult.tooManyRetries) {
            notifier.notifyPersistentFailure(account, detail)
        }
    }

    /**
     * The system stopped the run: neither a failure nor a completed run. The chunks the pull
     * finished are recorded, and the next run carries on from there (issue #70).
     */
    private fun stoppedRun(startedAt: Long, e: Exception) {
        logger.info {
            "sync stopped by the system after ${(System.currentTimeMillis() - startedAt) / MILLIS_PER_SECOND} s " +
                "(${e.javaClass.simpleName}); finished chunks are kept"
        }
    }

    /**
     * Every completed run stamps [UserPreferences.lastSyncRunAtMillis];
     * only a converged one — nothing failed, conflicted or quarantined
     * on either side — stamps [UserPreferences.lastSyncSuccessAtMillis].
     */
    /**
     * A background run that left contacts or changes behind says so once per change of the
     * count; the user asked for nothing, so a manual run (the card is on screen) stays quiet.
     * A run with no problems takes the notification down.
     */
    private fun announceProblems(writeReport: WriteReport, readReport: SyncReport, manual: Boolean) {
        val problems = readReport.failed + writeReport.failed + writeReport.quarantined + writeReport.conflicted
        when {
            problems == 0 -> {
                if (userPreferences.syncProblemsNotified != 0) notifier.clearContactProblems()
                userPreferences.syncProblemsNotified = 0
            }
            !manual && problems != userPreferences.syncProblemsNotified -> {
                notifier.notifyContactProblems(problems)
                userPreferences.syncProblemsNotified = problems
            }
        }
    }

    private fun recordRun(writeReport: WriteReport, readReport: SyncReport) {
        val now = System.currentTimeMillis()
        userPreferences.lastSyncRunAtMillis = now
        userPreferences.lastSyncErrorCode = null
        userPreferences.lastSyncFailedContacts = readReport.failed
        val converged = readReport.failed == 0 && writeReport.failed == 0 &&
            writeReport.conflicted == 0 && writeReport.quarantined == 0
        if (converged) userPreferences.lastSyncSuccessAtMillis = now
    }
}
