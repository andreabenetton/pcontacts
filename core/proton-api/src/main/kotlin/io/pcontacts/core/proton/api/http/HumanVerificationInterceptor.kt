// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Detects Proton's `Code: 9001` human-verification challenge and
 * surfaces it as a typed exception. Per plan §3.6 and risk #8 we
 * NEVER auto-retry 9001 — it must reach the user via the UI so they
 * can complete the captcha / recovery-email / SMS flow.
 *
 * Implementation: peek the response body and parse the top-level
 * JSON `Code` field via kotlinx.serialization. Parsing is resilient
 * to whitespace and field-order changes in Proton's responses. Body
 * peek is size-capped so a runaway response can't blow memory.
 *
 * `HumanVerificationRequiredException` extends `IOException` so
 * Retrofit propagates it from `Call.execute()` / suspend functions
 * without wrapping.
 */
class HumanVerificationInterceptor(
    private val maxPeekBytes: Long = DEFAULT_MAX_PEEK_BYTES,
    private val logger: Logger = RedactingLogger(tag = "HumanVerify", sink = NoOpSink)
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.body?.contentType()?.subtype ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return response

        val snippet = try {
            response.peekBody(maxPeekBytes).string()
        } catch (t: Throwable) {
            return response
        }
        if (isCode9001(snippet)) {
            response.close()
            logger.warn { "Proton returned Code:9001 (human verification) on ${chain.request().url.encodedPath}" }
            throw HumanVerificationRequiredException()
        }
        return response
    }

    companion object {
        const val DEFAULT_MAX_PEEK_BYTES: Long = 8 * 1024
        const val HUMAN_VERIFICATION_CODE = 9001

        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun isCode9001(body: String): Boolean = try {
            val code = lenientJson.parseToJsonElement(body)
                .jsonObject["Code"]
                ?.jsonPrimitive
                ?.int
            code == HUMAN_VERIFICATION_CODE
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Thrown when Proton's server requires human verification (Code 9001).
 * Callers MUST surface this to the user — never auto-retry. The
 * SyncAdapter maps this to `numAuthExceptions` so the sync framework
 * stops attempting until the user completes verification.
 */
class HumanVerificationRequiredException(
    message: String = "Proton requires human verification (Code 9001)"
) : IOException(message)
