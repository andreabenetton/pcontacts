// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class QuarantineReasonTest {

    @Test fun every_stored_reason_shape_gets_a_readable_line() {
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("HTTP 422, Proton code 2001"))
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("HTTP 400"))
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("Proton code 2501"))
        assertEquals(R.string.quarantined_reason_refused, quarantineReasonRes("HttpException: 404"))
        assertEquals(R.string.quarantined_reason_not_on_phone, quarantineReasonRes("contact not found locally"))
        assertEquals(R.string.quarantined_reason_network, quarantineReasonRes("network error"))
        assertEquals(R.string.quarantined_reason_other, quarantineReasonRes("internal error"))
        assertEquals(R.string.quarantined_reason_other, quarantineReasonRes("unknown op_type=9"))
    }
}
