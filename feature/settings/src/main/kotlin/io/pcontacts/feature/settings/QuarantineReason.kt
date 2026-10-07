// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

/**
 * A readable line for a failed change's persisted reason. The reason itself (an HTTP status
 * and Proton's code, or a short internal cause) stays below it in small print: it is what a
 * bug report needs, but not what the user reads first.
 */
internal fun quarantineReasonRes(reason: String): Int = when {
    reason.startsWith("HTTP") || reason.startsWith("Proton code") -> R.string.quarantined_reason_refused
    // Rows quarantined before 2.0 kept the exception's class name.
    reason.startsWith("HttpException") -> R.string.quarantined_reason_refused
    reason == "contact not found locally" -> R.string.quarantined_reason_not_on_phone
    reason == "network error" -> R.string.quarantined_reason_network
    // ADR-0027: a contact without an email cannot be in a Proton group.
    reason == "group needs an email" -> R.string.quarantined_reason_group_needs_email
    else -> R.string.quarantined_reason_other
}
