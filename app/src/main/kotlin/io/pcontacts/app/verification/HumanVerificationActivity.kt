// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import io.pcontacts.app.logging.AndroidLogcatSink
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.storage.EncryptedSecretStore
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app WebView that hosts Proton's hosted captcha page
 * (`verify.proton.me`). Once the user solves the challenge the page
 * posts a JSON envelope to the injected JS bridge; the activity
 * persists the resulting `{tokenType, tokenCode}` into [SecretStore]
 * and finishes with [RESULT_OK]. Subsequent OkHttp requests pick
 * the token up automatically via [HumanVerificationHeadersInterceptor]
 * — see ADR-0019 for the end-to-end flow.
 *
 * `[V]` Pattern ported from
 * `ProtonMail/protoncore_android/human-verification/presentation/.../ui/hv3/HV3DialogFragment.kt`
 * which uses the same WebView + `addJavascriptInterface` shape against
 * the same hosted page. `addJavascriptInterface` cannot be replaced by
 * an origin-scoped `WebMessageListener`: Proton's page calls
 * `window.AndroidInterface.dispatch(...)`, not `postMessage`.
 *
 * The WebView is constrained — defence in depth, none of it a sandbox
 * on its own:
 *   - The initial URL and every top-level navigation must be
 *     `https://verify.proton.me` (exact host, https only); a redirect
 *     elsewhere is refused. `[U]` The host is inferred from the web
 *     client and must equal `HumanVerificationInterceptor.VERIFICATION_HOST`.
 *   - Sub-frames and subresources must be https under `*.proton.me`;
 *     anything else gets an empty response (`shouldInterceptRequest`),
 *     which is also what keeps a foreign frame from ever reaching the
 *     bridge, since `addJavascriptInterface` exposes it to every frame.
 *     `[U]` The captcha's subresource hosts; a blocked one shows as an
 *     `HV: blocked` warning in the log.
 *   - JavaScript on (the captcha needs it); DOM storage, file and
 *     content access off; mixed content never.
 *   - The bridge accepts one success envelope, only while the top-level
 *     document is the verification page, and only printable-ASCII,
 *     bounded token/type values — they become HTTP header values, and
 *     OkHttp rejects anything else on every later request.
 */
class HumanVerificationActivity : ComponentActivity() {

    private val logger = RedactingLogger(tag = "HumanVerify", sink = AndroidLogcatSink())
    private val delivered = AtomicBoolean(false)

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
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { isVerificationPage(Uri.parse(it)) }
        if (url == null) {
            // Never log the URL: its query carries the verification token.
            logger.warn { "HV: refusing initial url — missing, or scheme or host not allowed" }
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
                addJavascriptInterface(Bridge(secretStoreSetter()) { url }, JS_INTERFACE_NAME)
                webViewClient = HostGuardWebViewClient(logger)
            }
        } catch (e: RuntimeException) {
            logger.error(e) { "WebView construction failed — cannot show verification" }
            null
        }
    }

    internal inner class Bridge(
        private val onToken: (token: String, type: String) -> Unit,
        /** The WebView's current top-level URL; read on the main thread. */
        private val currentUrl: () -> String?
    ) {
        // [V] Method name and single-String signature ported from
        // protoncore_android/human-verification/.../hv3/HV3DialogFragment.kt:291
        // — `@JavascriptInterface fun dispatch(response: String)`. The JS
        // side calls `window.AndroidInterface.dispatch(JSON.stringify(env))`.
        // Runs on a WebView worker thread; everything that touches the view
        // or the activity result moves to the main thread.
        @JavascriptInterface
        fun dispatch(response: String) {
            val (token, type) = parseSuccessEnvelope(response) ?: return
            runOnUiThread { deliver(token, type) }
        }

        private fun deliver(token: String, type: String) {
            if (!isVerificationPage(currentUrl()?.let(Uri::parse))) {
                logger.warn { "HV bridge: envelope from a page that is not the verification host, ignoring" }
                return
            }
            if (!delivered.compareAndSet(false, true)) return
            onToken(token, type)
            setResult(RESULT_OK)
            finish()
        }

        /** The `HUMAN_VERIFICATION_SUCCESS` envelope's token and type, or null for anything else. */
        private fun parseSuccessEnvelope(response: String): Pair<String, String>? {
            val root = try {
                JSONObject(response)
            } catch (_: Throwable) {
                logger.warn { "HV bridge: malformed JSON payload, ignoring" }
                return null
            }
            // Other envelope types (NOTIFICATION, RESIZE, etc.) are fired by
            // the captcha widget during interaction — silently ignore.
            if (root.optString("type") != "HUMAN_VERIFICATION_SUCCESS") return null
            val payload = root.optJSONObject("payload")
            val token = payload?.optString("token")?.takeIf { isHeaderSafe(it, MAX_TOKEN_LENGTH) }
            val type = payload?.optString("type")?.takeIf { isHeaderSafe(it, MAX_TYPE_LENGTH) }
            if (token == null || type == null) {
                logger.warn { "HV bridge: success envelope with missing or malformed token/type, ignoring" }
                return null
            }
            return token to type
        }
    }

    companion object {
        const val EXTRA_URL = "io.pcontacts.EXTRA_HV_URL"
        const val JS_INTERFACE_NAME = "AndroidInterface"
        const val PROTON_HOST_SUFFIX = "proton.me"

        /**
         * Must equal `HumanVerificationInterceptor.VERIFICATION_HOST` in
         * `:core:proton-api`; not imported because `:app` does not depend on
         * that module (ADR-0011).
         */
        const val VERIFICATION_HOST = "verify.proton.me"
        private const val MAX_TOKEN_LENGTH = 4096
        private const val MAX_TYPE_LENGTH = 32

        /** Non-empty, bounded, printable ASCII: the only shape an HTTP header value may take. */
        internal fun isHeaderSafe(value: String, max: Int): Boolean =
            value.length in 1..max && value.all { it.code in PRINTABLE_ASCII }

        private val PRINTABLE_ASCII = 0x21..0x7E
    }
}

/** The verification page over https (host exactly `verify.proton.me`): the only document and navigation target allowed. */
internal fun isVerificationPage(uri: Uri?): Boolean =
    uri?.scheme == "https" && uri.host == HumanVerificationActivity.VERIFICATION_HOST

/** An https URL on `proton.me` or one of its subdomains: what sub-frames and subresources may come from. */
internal fun isProtonHttps(uri: Uri?): Boolean {
    val host = uri?.host ?: return false
    val suffix = HumanVerificationActivity.PROTON_HOST_SUFFIX
    return uri.scheme == "https" && (host == suffix || host.endsWith(".$suffix"))
}

/**
 * Top-level navigation only to the verification page; sub-frames and
 * every other request only to https Proton hosts. Anything else is
 * refused (navigation) or answered with an empty body (resource).
 */
internal class HostGuardWebViewClient(private val logger: Logger) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return true
        val allowed = if (request.isForMainFrame) isVerificationPage(url) else isProtonHttps(url)
        if (!allowed) logger.warn { "HV: blocked navigation host=${url.host}" }
        return !allowed
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url
        if (isProtonHttps(url)) return null
        logger.warn { "HV: blocked resource host=${url?.host}" }
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
