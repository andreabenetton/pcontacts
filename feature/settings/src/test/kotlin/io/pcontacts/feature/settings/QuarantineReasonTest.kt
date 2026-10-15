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
        assertEquals(R.string.quarantined_reason_invalid_value, quarantineReasonRes("HTTP 422, Proton code 2001"))
        assertEquals(R.string.quarantined_reason_not_on_proton, quarantineReasonRes("Proton code 2501"))
        assertEquals(R.string.quarantined_reason_not_on_proton, quarantineReasonRes("HTTP 422, Proton code 2061"))
        assertEquals(R.string.quarantined_reason_not_allowed, quarantineReasonRes("HTTP 403, Proton code 2026"))
        assertEquals(R.string.quarantined_reason_not_allowed, quarantineReasonRes("HTTP 422, Proton code 2011"))
    }

    @Test fun an_undocumented_code_keeps_the_general_line() {
        // #71: Proton does not document 2060; the stored reason with the code is shown under the line.
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("HTTP 400, Proton code 2060"))
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("Proton code 9999"))
    }
}
