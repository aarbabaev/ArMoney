package com.armoney.android

import org.junit.Assert.*
import org.junit.Test

class ContractsTest {
    @Test fun exactMoneyAndOverflow() {
        assertEquals(1L, minor("0.01")); assertEquals(Long.MAX_VALUE, minor("92233720368547758.07"))
        listOf("0", "-1", "1.001", "1e2", "NaN", "92233720368547758.08", "1,00", " 1").forEach { assertTrue(it, runCatching { minor(it) }.isFailure) }
        assertEquals("AED 92233720368547758.07", money(Long.MAX_VALUE, "AED"))
    }
    @Test fun onlyAedWalletsAreEligibleForNewTransfers() {
        val wallet = Wallet("347637d3-a560-43d3-acd1-4c3f36e1ad9c", "b3848dc9-a620-4be7-bd69-e594ab0ad72e", "AED", "ACTIVE", "READY")
        assertEquals("AED", supportedCurrency(wallet.currency))
        assertTrue(wallet.ready)
        assertEquals(100L, minor("1.00"))
        assertEquals("AED 1.00", money(100, wallet.currency))
        listOf("EUR", "USD", "GBP", "JPY", "aed", " AED", "").forEach { currency ->
            assertTrue(currency, runCatching { supportedCurrency(currency) }.isFailure)
            assertFalse(currency, wallet.copy(currency = currency).ready)
        }
        assertFalse(wallet.copy(status = "CLOSED").ready)
        assertFalse(wallet.copy(provisioning_status = "PENDING").ready)
    }
    @Test fun exactPhoneAndIdentity() {
        listOf("50", "52", "54", "55", "56", "58").forEach { prefix ->
            val local = "${prefix}1234567"
            assertEquals("+971$local", recipientPhone(local))
            assertEquals("+971$local", phone("+971$local"))
        }
        listOf("", "+12025550123", "2025550123", "+971511234567", "+971531234567", "+971571234567", "+971591234567", "+9715012345678", "501234567", "+97150 1234567", " +971501234567").forEach {
            assertTrue(it, runCatching { phone(it) }.isFailure)
        }
        listOf("", "+12025550123", "+971501234567", "0501234567", "511234567", "531234567", "571234567", "591234567", "50123456", "5012345678", "50 1234567", " 501234567", "٥٠١٢٣٤٥٦٧").forEach {
            assertTrue(it, runCatching { recipientPhone(it) }.isFailure)
        }
        assertTrue(runCatching { uuid("1-1-1-1-1") }.isFailure)
        assertNull(wire.decodeFromString<Identity>("""{"id":"cb599d14-904a-4714-8504-280d1e689839","email":null}""").email)
    }
    @Test fun callbackRejectsSubstitutionDuplicatesAndWrongIssuer() {
        val base = Callback.REDIRECT
        assertEquals("abc", Callback.code("$base?code=abc&state=s&iss=https%3A%2F%2Fbank", "s", "https://bank"))
        listOf("$base?code=x&state=other", "$base?code=x&state=s&state=s", "$base?code=x&state=s#fragment", "com.armoney.android:/wrong?code=x&state=s", "$base?code=x&state=s&iss=https://evil", "$base?code=x&state=s&error=denied").forEach { assertTrue(it, runCatching { Callback.code(it, "s", "https://bank") }.isFailure) }
    }
    @Test fun uncertaintyCannotBeErasedByLaterRefusal() {
        val refusal = HttpFailure(409, "recipient_changed")
        assertTrue(mayDiscard(false, refusal)); assertFalse(mayDiscard(true, refusal))
        assertFalse(mayDiscard(false, HttpFailure(409, "idempotency_conflict")))
        assertFalse(mayDiscard(false, java.io.IOException()))
        assertFalse(mayDiscard(false, HttpFailure(503, null)))
    }
    @Test fun originCannotRedirectCredentials() {
        listOf("http://bank", "https://user@bank", "https://bank/path", "https://bank?x=y", "https://bank#x").forEach { assertTrue(runCatching { Configuration(it) }.isFailure) }
    }
}
