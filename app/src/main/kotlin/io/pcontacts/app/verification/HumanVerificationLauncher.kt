// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Wraps [CustomTabsIntent] to open Proton's human-verification
 * captcha page in a Chrome Custom Tab. Lives in `:app` because
 * `androidx.browser` is an Android-only dependency that `:core:*`
 * modules must not import (they are pure-JVM).
 *
 * The [url] is extracted from the 9001 response body by
 * [io.pcontacts.core.proton.api.http.HumanVerificationInterceptor]
 * and carried on [io.pcontacts.core.proton.api.http.HumanVerificationRequiredException.verificationUrl].
 * The URL shape is `[U]` — see the interceptor KDoc for caveats.
 */
object HumanVerificationLauncher {

    fun launch(context: Context, url: String) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(context, Uri.parse(url))
    }
}
