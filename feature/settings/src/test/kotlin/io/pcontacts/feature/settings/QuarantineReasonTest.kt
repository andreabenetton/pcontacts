// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class QuarantineReasonTest {

    @Test fun every_stored_reason_shape_gets_a_readable_line() {
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("HTTP 400"))
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("HttpException: 404"))
        assertEquals(R.string.quarantined_reason_not_on_phone, quarantineReasonRes("contact not found locally"))
        assertEquals(R.string.quarantined_reason_network, quarantineReasonRes("network error"))
        assertEquals(R.string.quarantined_reason_group_needs_email, quarantineReasonRes("group needs an email"))
        assertEquals(R.string.quarantined_reason_other, quarantineReasonRes("internal error"))
        assertEquals(R.string.quarantined_reason_other, quarantineReasonRes("unknown op_type=9"))
    }

    @Test fun a_refusal_proton_explains_by_its_code_says_what_was_refused() {
        assertEquals(R.string.quarantined_reason_email_format, quarantineReasonRes("HTTP 422, Proton code 2050"))
        assertEquals(R.string.quarantined_reason_email_domain, quarantineReasonRes("HTTP 422, Proton code 2059"))
        assertEquals(R.string.quarantined_reason_url, quarantineReasonRes("HTTP 422, Proton code 2052"))
        assertEquals(R.string.quarantined_reason_too_long, quarantineReasonRes("HTTP 422, Proton code 2024"))
        assertEquals(R.string.quarantined_reason_not_on_proton, quarantineReasonRes("Proton code 2501"))
        assertEquals(R.string.quarantined_reason_not_on_proton, quarantineReasonRes("HTTP 422, Proton code 2061"))
        assertEquals(R.string.quarantined_reason_not_allowed, quarantineReasonRes("HTTP 403, Proton code 2026"))
    }

    @Test fun a_card_proton_cannot_take_is_a_pcontacts_problem_to_report() {
        // Seen live 2026-10-15: each answers how the cards are built, never a value the user typed.
        assertEquals(R.string.quarantined_reason_format, quarantineReasonRes("HTTP 400, Proton code 2001"))
        assertEquals(R.string.quarantined_reason_format, quarantineReasonRes("HTTP 400, Proton code 2002"))
        assertEquals(R.string.quarantined_reason_format, quarantineReasonRes("HTTP 400, Proton code 2011"))
        assertEquals(R.string.quarantined_reason_format, quarantineReasonRes("HTTP 400, Proton code 13060"))
        assertEquals(R.string.quarantined_reason_format, quarantineReasonRes("Proton code 2001"))
    }

    @Test fun a_pgp_format_refusal_is_a_pcontacts_problem_to_report() {
        // #71: 2060 is PgpFormat in Proton's own clients; a create answers it without the HTTP status.
        assertEquals(R.string.quarantined_reason_format, quarantineReasonRes("HTTP 400, Proton code 2060"))
        assertEquals(R.string.quarantined_reason_format, quarantineReasonRes("Proton code 2060"))
    }

    @Test fun an_unknown_code_keeps_the_general_line() {
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("Proton code 9999"))
    }
}
