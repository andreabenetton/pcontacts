// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProtonCodeInterceptorTest {

    private lateinit var server: MockWebServer
    private val client by lazy { OkHttpClient.Builder().addInterceptor(ProtonCodeInterceptor()).build() }

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() { server.shutdown() }

    private fun get() = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

    private fun enqueue(status: Int, body: String, type: String = "application/json") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", type).setBody(body))
    }

    @Test fun `code 1000 and the 1001 multi-status envelope pass through`() {
        enqueue(200, """{"Code":1000,"Contact":{}}""")
        assertEquals(200, get().code)
        enqueue(200, """{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":2001}}]}""")
        assertEquals(200, get().code)
    }

    @Test fun `a non-success code inside a 2xx throws ProtonApiException`() {
        enqueue(200, """{"Code":2001,"Error":"Invalid input"}""")

        val ex = assertThrows(ProtonApiException::class.java) { get() }

        assertEquals(2001, ex.protonCode)
        assertEquals("Invalid input", ex.error)
        assertEquals("Proton Code:2001", ex.message)
    }

    @Test fun `an async call gets the ProtonApiException itself and nothing escapes on the dispatcher`() {
        enqueue(200, """{"Code":2011,"Error":"Refused"}""")
        val escaped = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped.set(e) }
        try {
            val failure = AtomicReference<IOException?>()
            val done = CountDownLatch(1)
            client.newCall(Request.Builder().url(server.url("/x")).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failure.set(e)
                    done.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    done.countDown()
                }
            })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            client.dispatcher.executorService.shutdown()
            client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)

            assertEquals(2011, (failure.get() as ProtonApiException).protonCode)
            assertNull("an exception escaped on OkHttp's dispatcher thread", escaped.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun `http errors and bodies without a code are left to the caller`() {
        enqueue(422, """{"Code":2002,"Error":"UID Field is missing"}""")
        assertEquals(422, get().code)
        enqueue(200, """{"Contacts":[]}""")
        assertEquals(200, get().code)
        enqueue(200, "<html/>", type = "text/html")
        assertEquals(200, get().code)
    }
}
