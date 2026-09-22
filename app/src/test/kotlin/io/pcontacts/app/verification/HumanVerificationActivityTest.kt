// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Looper
import android.util.AndroidRuntimeException
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HumanVerificationActivityTest {

    private val url = "https://verify.proton.me/?token=abc"
    private val successEnvelope =
        """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":"tok-123","type":"captcha"}}"""

    private fun intentWithUrl(url: String = this.url): Intent =
        Intent(ApplicationProvider.getApplicationContext(), HumanVerificationActivity::class.java)
            .putExtra(HumanVerificationActivity.EXTRA_URL, url)

    private fun request(url: String, mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private val guard = HostGuardWebViewClient(RedactingLogger(tag = "t", sink = NoOpSink))

    /** True when the guard refuses the navigation. */
    private fun blocksNavigation(url: String, mainFrame: Boolean): Boolean =
        guard.shouldOverrideUrlLoading(null, request(url, mainFrame))

    /** True when the guard answers the resource with an empty body. */
    private fun blocksResource(url: String): Boolean =
        guard.shouldInterceptRequest(null, request(url, mainFrame = false)) != null

    @Test
    fun missing_url_finishes_canceled() {
        val controller = Robolectric.buildActivity(HumanVerificationActivity::class.java)
        val activity = controller.get()
        controller.setup()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun http_or_foreign_host_initial_url_finishes_canceled_before_any_webview_is_built() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo())
        val badUrls = listOf(
            "http://verify.proton.me/?token=abc",
            "https://evil.proton.me.attacker.com/",
            "https://account.proton.me/"
        )
        for (bad in badUrls) {
            val controller = Robolectric.buildActivity(HumanVerificationActivity::class.java, intentWithUrl(bad))
            val activity = controller.get()
            var constructed = false
            activity.webViewFactory = { context ->
                constructed = true
                WebView(context)
            }
            controller.setup()
            assertFalse(bad, constructed)
            assertTrue(bad, activity.isFinishing)
            assertEquals(bad, Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        }
    }

    @Test
    fun no_webview_provider_finishes_canceled_without_constructing_webview() {
        // Robolectric's ShadowWebView default: getCurrentWebViewPackage() == null,
        // which is exactly the no-provider device this path exists for.
        val controller = Robolectric.buildActivity(HumanVerificationActivity::class.java, intentWithUrl())
        val activity = controller.get()
        var constructed = false
        activity.webViewFactory = { context ->
            constructed = true
            WebView(context)
        }
        controller.setup()
        assertFalse(constructed)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun webview_present_configures_and_loads_url() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo())
        val controller = Robolectric.buildActivity(HumanVerificationActivity::class.java, intentWithUrl())
        val activity = controller.get()
        activity.secretStoreSetter = { { _, _ -> } }
        controller.setup()
        assertFalse(activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val webView = content.getChildAt(0) as WebView
        assertTrue(webView.settings.javaScriptEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        assertEquals(url, shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun webview_constructor_failure_finishes_canceled() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo())
        val controller = Robolectric.buildActivity(HumanVerificationActivity::class.java, intentWithUrl())
        val activity = controller.get()
        activity.webViewFactory = { throw AndroidRuntimeException("provider died mid-flight") }
        controller.setup()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun host_guard_allows_only_the_verification_page_as_a_top_level_navigation() {
        assertFalse(blocksNavigation("https://verify.proton.me/challenge", mainFrame = true))
        assertTrue(blocksNavigation("http://verify.proton.me/", mainFrame = true))
        assertTrue(blocksNavigation("https://evil.proton.me.attacker.com/", mainFrame = true))
        assertTrue(blocksNavigation("https://account.proton.me/", mainFrame = true))
        assertTrue(guard.shouldOverrideUrlLoading(null, null as WebResourceRequest?))
    }

    @Test
    fun host_guard_allows_proton_https_sub_frames_and_blocks_foreign_ones() {
        assertFalse(blocksNavigation("https://verify-api.proton.me/frame", mainFrame = false))
        assertTrue(blocksNavigation("https://cdn.example.com/frame", mainFrame = false))
        assertTrue(blocksNavigation("http://verify.proton.me/frame", mainFrame = false))
    }

    @Test
    fun host_guard_answers_foreign_resources_with_an_empty_body() {
        assertFalse(blocksResource("https://verify-api.proton.me/x.js"))
        assertTrue(blocksResource("https://cdn.example.com/x.js"))
        assertTrue(blocksResource("http://verify.proton.me/x.js"))
    }

    private fun setUpWithBridge(): Triple<HumanVerificationActivity, WebView, MutableList<Pair<String, String>>> {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo())
        val controller = Robolectric.buildActivity(HumanVerificationActivity::class.java, intentWithUrl())
        val activity = controller.get()
        val delivered = mutableListOf<Pair<String, String>>()
        activity.secretStoreSetter = { { token, type -> delivered += token to type } }
        controller.setup()
        val webView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
        return Triple(activity, webView, delivered)
    }

    private fun bridgeOf(webView: WebView): HumanVerificationActivity.Bridge {
        val bridge = shadowOf(webView).getJavascriptInterface(HumanVerificationActivity.JS_INTERFACE_NAME)
        return bridge as HumanVerificationActivity.Bridge
    }

    @Test
    fun bridge_accepts_the_success_envelope_once_and_finishes_ok() {
        val (activity, webView, delivered) = setUpWithBridge()

        bridgeOf(webView).dispatch(successEnvelope)
        bridgeOf(webView).dispatch(successEnvelope)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("tok-123" to "captcha"), delivered)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }

    @Test
    fun bridge_ignores_the_envelope_when_the_page_is_not_the_verification_host() {
        val (activity, webView, delivered) = setUpWithBridge()
        webView.loadUrl("https://evil.example/")

        bridgeOf(webView).dispatch(successEnvelope)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(delivered.isEmpty())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun bridge_rejects_non_ascii_or_oversized_tokens() {
        val (activity, webView, delivered) = setUpWithBridge()
        val accented = """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":"toké","type":"captcha"}}"""
        val longToken = "a".repeat(5000)
        val huge = """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":"$longToken","type":"captcha"}}"""

        bridgeOf(webView).dispatch(accented)
        bridgeOf(webView).dispatch(huge)
        bridgeOf(webView).dispatch("""{"type":"NOTIFICATION"}""")
        bridgeOf(webView).dispatch("not json")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(delivered.isEmpty())
        assertFalse(activity.isFinishing)
    }
}
