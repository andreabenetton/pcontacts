// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Detects Proton's version-rejected responses and surfaces them as a
 * typed [AppVersionRejectedException]. This lets callers distinguish
 * "our hardcoded `x-pm-appversion` has aged out of Proton's sliding
 * acceptance window" from generic auth or IO failures.
 *
 * `[V]` Proton rejects outdated `x-pm-appversion` values with
 * `Code: 5003` (force-upgrade) in the JSON body. The HTTP status
 * code varies (401, 422, or 400 depending on how far out of window
 * the version is), so we key detection on the JSON Code field, not
 * the HTTP status.
 *
 * `[A]` Code 5004 (API version unsupported) may also indicate a
 * version-related rejection. We treat both 5003 and 5004 as
 * version rejections to be safe.
 *
 * Implementation mirrors [HumanVerificationInterceptor]: peek the
 * response body, parse the top-level `Code` field, throw if it
 * matches a version-rejection code. Body peek is size-capped.
 *
 * This interceptor MUST run after [HumanVerificationInterceptor] in
 * the chain so that 9001 is caught first (it's more specific and has
 * its own recovery path).
 */
class AppVersionRejectionInterceptor(
    private val maxPeekBytes: Long = DEFAULT_MAX_PEEK_BYTES,
    private val logger: Logger = RedactingLogger(tag = "AppVersion", sink = NoOpSink)
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.body.contentType()?.subtype ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return response

        val snippet = try {
            response.peekBody(maxPeekBytes).string()
        } catch (_: Throwable) {
            logger.warn { "peekBody failed on ${chain.request().url.encodedPath}; skipping version check" }
            return response
        }

        val code = extractCode(snippet) ?: return response
        if (code in VERSION_REJECTION_CODES) {
            response.close()
            logger.warn {
                "Proton rejected x-pm-appversion (Code:$code) on ${chain.request().url.encodedPath}"
            }
            throw AppVersionRejectedException(code)
        }
        return response
    }

    companion object {
        const val DEFAULT_MAX_PEEK_BYTES: Long = 8 * 1024

        // [V] 5003 = AppVersionBadAppVersion (force upgrade).
        // [A] 5004 = AppVersionBadApiVersion (API version unsupported).
        val VERSION_REJECTION_CODES: Set<Int> = setOf(5003, 5004)

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun extractCode(body: String): Int? = try {
            lenientJson.parseToJsonElement(body)
                .jsonObject["Code"]
                ?.jsonPrimitive
                ?.int
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Thrown when Proton's server rejects the `x-pm-appversion` header
 * because the hardcoded version has aged out of the sliding acceptance
 * window. This is NOT a transient failure — retrying will not help.
 * The app needs a code update to bump `ProtonApiConfig.DEFAULT_APP_VERSION`.
 *
 * Callers should surface a user-visible message advising them to
 * update the app. The SyncAdapter maps this to `numAuthExceptions`
 * so the sync framework stops retrying.
 */
class AppVersionRejectedException(
    val protonCode: Int,
    message: String = "Proton rejected x-pm-appversion (Code $protonCode) — app update required"
) : IOException(message)
