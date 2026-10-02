package com.armoney.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class HttpTest {
    private class Connection(private val status: Int = 200, private val response: ByteArray = "{}".toByteArray()) : HttpsURLConnection(URL("https://bank.example/v1/payments")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentType() = "application/json"
        override fun getInputStream() = ByteArrayInputStream(response)
        override fun getErrorStream() = ByteArrayInputStream(response)
        override fun getOutputStream() = sent
    }
    @Test fun credentialsAndImmutableCommandStayOnConfiguredOrigin() = runBlocking {
        val connection = Connection()
        var destination = ""
        val http = BankHttp(Configuration("https://bank.example")) { destination = it.toString(); connection }
        val payload = """{"amount_minor":9223372036854775807}"""
        http.send("/v1/payments", "POST", payload, "a".repeat(43), "stable-key")
        assertEquals("https://bank.example/v1/payments", destination)
        assertEquals("Bearer " + "a".repeat(43), connection.getRequestProperty("Authorization"))
        assertEquals("stable-key", connection.getRequestProperty("Idempotency-Key"))
        assertEquals(payload, connection.sent.toString("UTF-8"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(10_000, connection.connectTimeout); assertEquals(15_000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }
    @Test fun redirectsErrorsAndOversizedResponsesCannotLookSuccessful() = runBlocking {
        val redirect = Connection(302)
        val redirectError = runCatching { BankHttp(Configuration("https://bank.example")) { redirect }.send("/v1/auth/me") }.exceptionOrNull()
        assertEquals(302, (redirectError as HttpFailure).status)
        val refusal = Connection(409, """{"error":"recipient_changed"}""".toByteArray())
        val error = runCatching { BankHttp(Configuration("https://bank.example")) { refusal }.send("/v1/payments") }.exceptionOrNull()
        assertEquals("recipient_changed", (error as HttpFailure).code)
        val large = Connection(200, ByteArray(1_048_577))
        assertTrue(runCatching { BankHttp(Configuration("https://bank.example")) { large }.send("/v1/wallets") }.isFailure)
        assertTrue(large.disconnected)
    }
    @Test fun emptyReadBodyIsNotAnInventedJsonObject() = runBlocking {
        val connection = Connection()
        BankHttp(Configuration("https://bank.example")) { connection }.send("/v1/notifications/123/read", "POST", token = "a".repeat(43))
        assertEquals(0, connection.sent.size()); assertFalse(connection.doOutput)
    }
    @Test fun onlyStrictJsonRefusalCanReleaseFirstAttempt() {
        val good = """{"error":"recipient_changed"}"""
        assertEquals("recipient_changed", strictErrorCode("application/json; charset=utf-8", good, good.length))
        listOf("""{"error":"idempotency_conflict","error":"recipient_changed"}""", """{"error":"recipient_changed","extra":true}""", """{"error":409}""").forEach {
            assertNull(strictErrorCode("application/json", it, it.length))
        }
        assertNull(strictErrorCode("text/html", good, good.length))
        assertNull(strictErrorCode(null, good, good.length))
        assertNull(strictErrorCode("application/json", good, 4097))
    }
}
