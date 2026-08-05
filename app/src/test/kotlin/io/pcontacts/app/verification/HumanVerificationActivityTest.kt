// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.util.AndroidRuntimeException
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
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

    private fun intentWithUrl(): Intent =
        Intent(ApplicationProvider.getApplicationContext(), HumanVerificationActivity::class.java)
            .putExtra(HumanVerificationActivity.EXTRA_URL, url)

    @Test
    fun missing_url_finishes_canceled() {
        val controller = Robolectric.buildActivity(HumanVerificationActivity::class.java)
        val activity = controller.get()
        controller.setup()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
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
}
