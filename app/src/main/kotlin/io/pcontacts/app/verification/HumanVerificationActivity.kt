// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.annotation.SuppressLint
import android.content.Context
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

    // Test seam: the only way to exercise the constructor-failure branch
    // (a real WebView never throws under Robolectric).
    internal var webViewFactory: (Context) -> WebView = ::WebView

    // Test seam: EncryptedSecretStore needs the AndroidKeyStore provider,
    // which unit tests don't have. Production timing is unchanged — the
    // store is still created eagerly while onCreate configures the view.
    internal var secretStoreSetter: () -> ((token: String, type: String) -> Unit) = {
        val store = EncryptedSecretStore.create(this)
        val setter: (token: String, type: String) -> Unit = { token, type ->
            store.setHumanVerificationToken(token)
            store.setHumanVerificationTokenType(type)
        }
        setter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val webView = createGuardedWebView()
        if (webView == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContentView(webView)
        webView.loadUrl(url)
    }

    /**
     * Builds the configured WebView, or returns null when the device has
     * no usable WebView provider (common on de-Googled distributions such
     * as MuditaOS). `[V]` `getCurrentWebViewPackage` exists since API 26
     * (== minSdk) and returns null when no provider is enabled. The catch
     * covers a provider that is disabled or breaks between the check and
     * construction — the framework throws `AndroidRuntimeException` (a
     * `RuntimeException`) from the WebView constructor in that case.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createGuardedWebView(): WebView? {
        if (WebView.getCurrentWebViewPackage() == null) {
            logger.warn { "no enabled WebView provider — cannot show verification" }
            return null
        }
        return try {
            webViewFactory(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // mixedContentMode default (NEVER_ALLOW) is what we want.
                addJavascriptInterface(Bridge(secretStoreSetter()), JS_INTERFACE_NAME)
                webViewClient = HostGuardWebViewClient()
            }
        } catch (e: RuntimeException) {
            logger.error(e) { "WebView construction failed — cannot show verification" }
            null
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
