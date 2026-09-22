// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Proton reports application-level failure inside HTTP 2xx bodies as a
 * non-success `Code`; a caller that reads the payload of such a body
 * acts on data that is not there. This interceptor turns every 2xx
 * JSON body whose `Code` is neither `1000` (success, `[V]`) nor `1001`
 * (multi-status envelope of the batch endpoints, `[V]` — the per-item
 * codes are the callers' to check) into a [ProtonApiException].
 *
 * Runs after the human-verification and app-version interceptors, so
 * 9001 / 12087 / 5003 / 5004 keep their specific exceptions. A body
 * without a `Code` (or one that is not JSON) is left alone.
 */
class ProtonCodeInterceptor(
    private val maxPeekBytes: Long = DEFAULT_MAX_PEEK_BYTES,
    private val logger: Logger = RedactingLogger(tag = "ProtonCode", sink = NoOpSink)
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        val contentType = response.body.contentType()?.subtype ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return response

        val snippet = try {
            response.peekBody(maxPeekBytes).string()
        } catch (_: Throwable) {
            logger.warn { "peekBody failed on ${chain.request().url.encodedPath}; skipping code check" }
            return response
        }
        val envelope = parse(snippet) ?: return response
        if (envelope.code in ACCEPTED_CODES) return response

        response.close()
        logger.warn { "Proton Code:${envelope.code} inside HTTP ${response.code} on ${chain.request().url.encodedPath}" }
        throw ProtonApiException(envelope.code, envelope.error)
    }

    internal data class Envelope(val code: Int, val error: String?)

    companion object {
        const val DEFAULT_MAX_PEEK_BYTES: Long = 8 * 1024
        const val SUCCESS_CODE = 1000
        const val MULTI_STATUS_CODE = 1001
        val ACCEPTED_CODES: Set<Int> = setOf(SUCCESS_CODE, MULTI_STATUS_CODE)

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** The `Code` (and `Error`) of a JSON object body, or null when there is no `Code` at all. */
        internal fun parse(body: String): Envelope? = try {
            val root = lenientJson.parseToJsonElement(body).jsonObject
            root["Code"]?.jsonPrimitive?.int?.let { code ->
                Envelope(code, root["Error"]?.jsonPrimitive?.contentOrNull)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * A 2xx response whose Proton `Code` is not a success code. Not a
 * transport failure and not retryable as one: the request reached
 * Proton and was refused. The message carries the code only; the
 * server's text stays in [error] for the log, never for the user.
 */
class ProtonApiException(val protonCode: Int, val error: String?) :
    RuntimeException("Proton Code:$protonCode")
