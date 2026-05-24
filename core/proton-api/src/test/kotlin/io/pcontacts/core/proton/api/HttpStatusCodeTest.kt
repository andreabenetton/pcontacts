// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class HttpStatusCodeTest {

    @Test fun httpException_returns_status_code() {
        val rawResponse = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(422)
            .message("Unprocessable Entity")
            .build()
        val exception = HttpException(
            Response.error<String>("".toResponseBody(), rawResponse)
        )

        assertEquals(422, exception.httpStatusCode())
    }

    @Test fun non_http_exception_returns_null() {
        assertNull(RuntimeException("boom").httpStatusCode())
    }

    @Test fun io_exception_returns_null() {
        assertNull(java.io.IOException("network").httpStatusCode())
    }
}
