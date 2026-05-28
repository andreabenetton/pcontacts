// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

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
 * When the 9001 body carries a `Details` object, the interceptor
 * extracts:
 * - `HumanVerificationToken` `[U]` — an opaque token the client
 *   passes back after solving the challenge.
 * - `HumanVerificationMethods` `[U]` — array of accepted methods
 *   (e.g. `["captcha","email","sms"]`).
 *
 * If `"captcha"` is among the methods, the exception carries a
 * constructed verification URL pointing to `https://verify.proton.me`
 * with the token as a query parameter `[U]`. The exact URL shape is
 * inferred from the Proton web client's challenge flow and has NOT
 * been validated against a live 9001 response — callers MUST handle
 * a null URL gracefully (fail-closed: show a manual-instructions
 * dialog instead of opening a Custom Tab).
 *
 * `HumanVerificationRequiredException` extends `IOException` so
 * Retrofit propagates it from `Call.execute()` / suspend functions
 * without wrapping.
 */
// [tokens] is consulted on every 9001 — when the request that triggered
// the 9001 was already carrying x-pm-human-verification-token, the stored
// token is stale. Clearing it via [tokens].clear() drops the headers from
// the next attempt so the UI re-prompts instead of looping the stale token.
// Defaults to Empty so existing call sites without a token store still work.
class HumanVerificationInterceptor(
    private val maxPeekBytes: Long = DEFAULT_MAX_PEEK_BYTES,
    private val tokens: HumanVerificationTokenSource = HumanVerificationTokenSource.Empty,
    private val logger: Logger = RedactingLogger(tag = "HumanVerify", sink = NoOpSink)
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val contentType = response.body.contentType()?.subtype ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return response

        val snippet = try {
            response.peekBody(maxPeekBytes).string()
        } catch (_: Throwable) {
            logger.warn { "peekBody failed on ${request.url.encodedPath}; skipping 9001 check" }
            return response
        }

        val parsed = parse9001(snippet) ?: return response
        response.close()
        if (request.header("x-pm-human-verification-token") != null) {
            // Stale token — drop it so the next attempt re-prompts the user.
            logger.warn { "Code:9001 with HV headers already set — clearing stored token" }
            tokens.clear()
        } else {
            logger.warn { "Proton returned Code:9001 (human verification) on ${request.url.encodedPath}" }
        }
        throw HumanVerificationRequiredException(verificationUrl = parsed.verificationUrl)
    }

    companion object {
        const val DEFAULT_MAX_PEEK_BYTES: Long = 8 * 1024
        const val HUMAN_VERIFICATION_CODE = 9001

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Returns non-null if `body` is a JSON object with `Code: 9001`.
         * The returned [Parsed9001] carries the verification URL when
         * extractable, or null when the `Details` block is absent or
         * does not contain the expected fields.
         */
        internal fun parse9001(body: String): Parsed9001? = try {
            val root = lenientJson.parseToJsonElement(body).jsonObject
            val code = root["Code"]?.jsonPrimitive?.int
            if (code != HUMAN_VERIFICATION_CODE) {
                null
            } else {
                val url = extractVerificationUrl(root)
                Parsed9001(verificationUrl = url)
            }
        } catch (_: Exception) {
            null
        }

        /**
         * Extracts a captcha verification URL from the `Details` block.
         *
         * `[U]` Expected shape (inferred from WebClients
         * `packages/shared/lib/api/helpers/withApiHandlers.ts`):
         * ```json
         * {
         *   "Details": {
         *     "HumanVerificationToken": "<opaque>",
         *     "HumanVerificationMethods": ["captcha", "email", "sms"]
         *   }
         * }
         * ```
         *
         * Returns null if:
         * - `Details` is absent
         * - `HumanVerificationToken` is absent or blank
         * - `"captcha"` is not among `HumanVerificationMethods`
         */
        private fun extractVerificationUrl(
            root: Map<String, kotlinx.serialization.json.JsonElement>
        ): String? = try {
            val details = root["Details"]?.jsonObject ?: return null
            val token = details["HumanVerificationToken"]
                ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return null
            val methods = details["HumanVerificationMethods"]
                ?.jsonArray?.mapNotNull {
                    try { it.jsonPrimitive.content } catch (_: Exception) { null }
                } ?: return null
            if ("captcha" !in methods) return null
            "https://verify.proton.me/?token=$token&methods=captcha"
        } catch (_: Exception) {
            null
        }
    }
}

internal data class Parsed9001(val verificationUrl: String?)

/**
 * Thrown when Proton's server requires human verification (Code 9001).
 * Callers MUST surface this to the user — never auto-retry. The
 * SyncAdapter maps this to `numAuthExceptions` so the sync framework
 * stops attempting until the user completes verification.
 *
 * [verificationUrl] carries the captcha URL when the server's 9001
 * response included a `Details.HumanVerificationToken` with
 * `"captcha"` in `HumanVerificationMethods` `[U]`. When null,
 * callers should show a manual-instructions dialog instead of
 * opening a browser — this is the fail-closed branch.
 */
class HumanVerificationRequiredException(
    val verificationUrl: String? = null,
    message: String = "Proton requires human verification (Code 9001)"
) : IOException(message)
