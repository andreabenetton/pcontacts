// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import io.pcontacts.app.logging.AndroidLogcatSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.storage.EncryptedSecretStore
import org.json.JSONObject

/**
 * In-app WebView that hosts Proton's hosted captcha page
 * (`verify.proton.me`). Once the user solves the challenge the page
 * posts a JSON envelope to the injected JS bridge; the activity
 * persists the resulting `{tokenType, tokenCode}` into [SecretStore]
 * and finishes with [RESULT_OK]. Subsequent OkHttp requests pick
 * the token up automatically via [HumanVerificationHeadersInterceptor]
 * — see ADR / plan §20 for the end-to-end flow.
 *
 * `[V]` Pattern ported from
 * `ProtonMail/protoncore_android/human-verification/presentation/.../ui/hv3/HV3DialogFragment.kt`
 * which uses the same WebView + `addJavascriptInterface` shape against
 * the same hosted page.
 *
 * The WebView is constrained:
 *   - URL loading is gated to `*.proton.me` only — any redirect
 *     outside that domain is refused, which prevents the captcha page
 *     from navigating to a third-party host that could exfiltrate
 *     (CLAUDE.md "no network outside *.proton.me" applies here too,
 *     even though this isn't `:core:proton-api`).
 *   - JavaScript is on (required for the captcha widget); DOM storage
 *     is off; no file or content access; no mixed content.
 *   - The bridge accepts only the success envelope; any other shape
 *     is logged and discarded.
 */
class HumanVerificationActivity : ComponentActivity() {

    private val logger = RedactingLogger(tag = "HumanVerify", sink = AndroidLogcatSink())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // mixedContentMode default (NEVER_ALLOW) is what we want.
            addJavascriptInterface(Bridge(secretStoreSetter()), JS_INTERFACE_NAME)
            webViewClient = HostGuardWebViewClient()
        }

        setContentView(webView)
        webView.loadUrl(url)
    }

    private fun secretStoreSetter(): (token: String, type: String) -> Unit {
        val store = EncryptedSecretStore.create(this)
        return { token, type ->
            store.setHumanVerificationToken(token)
            store.setHumanVerificationTokenType(type)
        }
    }

    private inner class Bridge(
        private val onToken: (token: String, type: String) -> Unit
    ) {
        // [V] Method name and single-String signature ported from
        // protoncore_android/human-verification/.../hv3/HV3DialogFragment.kt:291
        // — `@JavascriptInterface fun dispatch(response: String)`. The JS
        // side calls `window.AndroidInterface.dispatch(JSON.stringify(env))`.
        @JavascriptInterface
        fun dispatch(response: String) {
            val root = try {
                JSONObject(response)
            } catch (_: Throwable) {
                logger.warn { "HV bridge: malformed JSON payload, ignoring" }
                return
            }
            if (root.optString("type") != "HUMAN_VERIFICATION_SUCCESS") {
                // Other envelope types (NOTIFICATION, RESIZE, etc.) are fired
                // by the captcha widget during interaction — silently ignore.
                return
            }
            val payload = root.optJSONObject("payload")
            val token = payload?.optString("token")?.takeIf { it.isNotBlank() }
            val type = payload?.optString("type")?.takeIf { it.isNotBlank() }
            if (token == null || type == null) {
                logger.warn { "HV bridge: success envelope missing token/type, ignoring" }
                return
            }
            onToken(token, type)
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private class HostGuardWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val host = request?.url?.host ?: return true
            // Block navigation to anything outside *.proton.me, including
            // redirects from a compromised verify.proton.me itself.
            return !(host == PROTON_HOST_SUFFIX || host.endsWith(".$PROTON_HOST_SUFFIX"))
        }
    }

    companion object {
        const val EXTRA_URL = "io.pcontacts.EXTRA_HV_URL"
        const val JS_INTERFACE_NAME = "AndroidInterface"
        const val PROTON_HOST_SUFFIX = "proton.me"
    }
}
