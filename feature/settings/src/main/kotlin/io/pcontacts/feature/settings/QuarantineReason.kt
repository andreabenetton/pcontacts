// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

/**
 * A readable line for a failed change's persisted reason. The reason itself (an HTTP status
 * and Proton's code, or a short internal cause) stays below it in small print: it is what a
 * bug report needs, but not what the user reads first.
 */
internal fun quarantineReasonRes(reason: String): Int = when {
    reason.startsWith("HTTP") || reason.startsWith("Proton code") -> protonRefusalRes(reason)
    // Rows quarantined before 2.0 kept the exception's class name.
    reason.startsWith("HttpException") -> R.string.quarantined_reason_refused
    reason == "contact not found locally" -> R.string.quarantined_reason_not_on_phone
    reason == "network error" -> R.string.quarantined_reason_network
    // ADR-0027: a contact without an email cannot be in a Proton group.
    reason == "group needs an email" -> R.string.quarantined_reason_group_needs_email
    else -> R.string.quarantined_reason_other
}

/**
 * A refusal in plain words where Proton's code says what it refused. `[V]` the codes are named in
 * WebClients `packages/shared/lib/errors.ts` (2501 seen live, 2026-10-01); any other code — 2060
 * is undocumented (#71) — keeps the general line, and the stored reason with the code is shown
 * under it either way.
 */
private fun protonRefusalRes(reason: String): Int = when (PROTON_CODE.find(reason)?.groupValues?.get(1)?.toInt()) {
    EMAIL_FORMAT -> R.string.quarantined_reason_email_format
    INVALID_DOMAIN_NAME -> R.string.quarantined_reason_email_domain
    INVALID_URL -> R.string.quarantined_reason_url
    INVALID_VALUE -> R.string.quarantined_reason_invalid_value
    INVALID_ID, NOT_EXISTS -> R.string.quarantined_reason_not_on_proton
    NOT_ALLOWED, PERMISSION_DENIED -> R.string.quarantined_reason_not_allowed
    else -> R.string.quarantined_reason_refused
}

private val PROTON_CODE = Regex("Proton code (\\d+)")
private const val INVALID_VALUE = 2001
private const val NOT_ALLOWED = 2011
private const val PERMISSION_DENIED = 2026
private const val EMAIL_FORMAT = 2050
private const val INVALID_URL = 2052
private const val INVALID_DOMAIN_NAME = 2059
private const val INVALID_ID = 2061
private const val NOT_EXISTS = 2501
