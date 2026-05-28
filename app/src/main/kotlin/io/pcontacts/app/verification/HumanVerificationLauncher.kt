// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.content.Context
import android.content.Intent

/**
 * Starts the in-app [HumanVerificationActivity] WebView so the user
 * can solve Proton's captcha. The activity persists the resulting
 * verification token into `SecretStore`, after which every Proton API
 * call automatically attaches the
 * `x-pm-human-verification-token{,-type}` headers.
 *
 * **Why not Chrome Custom Tabs?** Custom Tabs solve the captcha in
 * Chrome's process — its cookie/session jar never crosses back into
 * our OkHttp client, so the original 9001 always re-fires on the next
 * request. The in-app WebView pattern matches Proton's official
 * Android stack
 * (`protoncore_android/human-verification/.../HV3DialogFragment.kt`).
 *
 * The [url] is extracted from the 9001 response body by
 * [io.pcontacts.core.proton.api.http.HumanVerificationInterceptor] and
 * carried on
 * [io.pcontacts.core.proton.api.http.HumanVerificationRequiredException.verificationUrl].
 */
object HumanVerificationLauncher {

    fun launch(context: Context, url: String) {
        val intent = Intent(context, HumanVerificationActivity::class.java).apply {
            putExtra(HumanVerificationActivity.EXTRA_URL, url)
            // Allows launching from a non-Activity Context (e.g. the
            // SyncNotifier path) — matches the contract Chrome Custom
            // Tabs previously had.
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}
