// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HumanVerificationInterceptorTest {

    private lateinit var server: MockWebServer
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HumanVerificationInterceptor())
            .build()
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() { server.shutdown() }

    private fun get() = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

    // ---- Integration tests via MockWebServer ----

    @Test fun `9001 without Details throws with null verificationUrl`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":9001,"Error":"Human verification required"}""")
        )
        val ex = assertThrows(HumanVerificationRequiredException::class.java) { get() }
        assertNull(ex.verificationUrl)
    }

    @Test fun `9001 with captcha Details throws with verificationUrl`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"Code":9001,"Error":"Human verification required",""" +
                        """"Details":{"HumanVerificationToken":"abc123",""" +
                        """"HumanVerificationMethods":["captcha","email"]}}"""
                )
        )
        val ex = assertThrows(HumanVerificationRequiredException::class.java) { get() }
        assertNotNull(ex.verificationUrl)
        assertEquals("https://verify.proton.me/?token=abc123&methods=captcha", ex.verificationUrl)
    }

    @Test fun `9001 with non_captcha methods throws with null verificationUrl`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"Code":9001,"Details":{"HumanVerificationToken":"t1",""" +
                        """"HumanVerificationMethods":["email","sms"]}}"""
                )
        )
        val ex = assertThrows(HumanVerificationRequiredException::class.java) { get() }
        assertNull(ex.verificationUrl)
    }

    @Test fun non_9001_json_body_passes_through_unchanged() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":1000,"OK":true}""")
        )
        val response = get()
        assertEquals(200, response.code)
        assertEquals("""{"Code":1000,"OK":true}""", response.body.string())
    }

    @Test fun non_json_body_is_not_inspected_even_if_it_contains_9001() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("garbage \"Code\":9001 garbage")
        )
        val response = get()
        assertEquals(200, response.code)
    }

    @Test fun `9001 with Code field not first is caught`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Details":{},"Code":9001,"Error":"v"}""")
        )
        assertThrows(HumanVerificationRequiredException::class.java) { get() }
    }

    @Test fun `9001 with whitespace around colon is caught`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "Code" : 9001 , "Error" : "verify" }""")
        )
        assertThrows(HumanVerificationRequiredException::class.java) { get() }
    }

    @Test fun `9001 with pretty-printed json is caught`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\n  \"Error\": \"Human verification required\",\n  \"Code\": 9001\n}")
        )
        assertThrows(HumanVerificationRequiredException::class.java) { get() }
    }

    // ---- Unit tests for parse9001 directly ----

    @Test fun parse9001_minified() {
        assertNotNull(HumanVerificationInterceptor.parse9001("""{"Code":9001}"""))
    }

    @Test fun parse9001_with_spaces() {
        assertNotNull(HumanVerificationInterceptor.parse9001("""{ "Code" : 9001 }"""))
    }

    @Test fun parse9001_different_code() {
        assertNull(HumanVerificationInterceptor.parse9001("""{"Code":1000}"""))
    }

    @Test fun parse9001_no_Code_field() {
        assertNull(HumanVerificationInterceptor.parse9001("""{"Error":"x"}"""))
    }

    @Test fun parse9001_invalid_json() {
        assertNull(HumanVerificationInterceptor.parse9001("not json at all"))
    }

    @Test fun parse9001_code_as_string_9001_still_detected() {
        assertNotNull(HumanVerificationInterceptor.parse9001("""{"Code":"9001"}"""))
    }

    @Test fun parse9001_code_as_non_numeric_string() {
        assertNull(HumanVerificationInterceptor.parse9001("""{"Code":"not-a-number"}"""))
    }

    @Test fun parse9001_with_captcha_details_extracts_url() {
        val body = """{"Code":9001,"Details":{"HumanVerificationToken":"tok42","HumanVerificationMethods":["captcha"]}}"""
        val result = HumanVerificationInterceptor.parse9001(body)
        assertNotNull(result)
        assertEquals("https://verify.proton.me/?token=tok42&methods=captcha", result!!.verificationUrl)
    }

    @Test fun parse9001_with_empty_details_returns_null_url() {
        val result = HumanVerificationInterceptor.parse9001("""{"Code":9001,"Details":{}}""")
        assertNotNull(result)
        assertNull(result!!.verificationUrl)
    }

    @Test fun parse9001_with_missing_token_returns_null_url() {
        val body = """{"Code":9001,"Details":{"HumanVerificationMethods":["captcha"]}}"""
        val result = HumanVerificationInterceptor.parse9001(body)
        assertNotNull(result)
        assertNull(result!!.verificationUrl)
    }

    @Test fun parse9001_with_blank_token_returns_null_url() {
        val body = """{"Code":9001,"Details":{"HumanVerificationToken":"","HumanVerificationMethods":["captcha"]}}"""
        val result = HumanVerificationInterceptor.parse9001(body)
        assertNotNull(result)
        assertNull(result!!.verificationUrl)
    }

    @Test fun parse9001_without_captcha_method_returns_null_url() {
        val body = """{"Code":9001,"Details":{"HumanVerificationToken":"tok","HumanVerificationMethods":["email","sms"]}}"""
        val result = HumanVerificationInterceptor.parse9001(body)
        assertNotNull(result)
        assertNull(result!!.verificationUrl)
    }
}
