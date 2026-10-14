package com.armoney.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AssistantTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val session = SavedSession("https://bank.example", Session("a".repeat(43), "Bearer", "2026-10-13T12:30:00Z"), Identity("00000000-0000-0000-0000-000000000001"))
    private val raw = """{"reply":"Current balance","outcome":"answered","citations":[],"facts":{"wallets":[{"wallet_id":"00000000-0000-0000-0000-000000000002","currency":"AED","balance_minor":9223372036854775807}],"last_transaction":null},"provenance":[{"source":"wallet-service","fetched_at":"2026-10-13T12:00:00Z","scope":"current owner wallets"}]}"""
    @Test fun exactIntegerFactsAndDubaiTime() {
        val answer = assistantAnswer(raw)
        assertEquals(Long.MAX_VALUE, answer.facts.wallets!!.single().balance_minor)
        assertEquals("AED 92233720368547758.07", money(answer.facts.wallets.single().balance_minor, "AED"))
        assertEquals("13 Oct 2026 16:00:00 Asia/Dubai", assistantTime("2026-10-13T12:00:00Z"))
    }
    @Test fun malformedFactsCannotBecomeBalances() {
        listOf(
            raw.replace("9223372036854775807", "9223372036854775808"),
            raw.replace("9223372036854775807", "1.5"),
            raw.replace("9223372036854775807", "\"123\""),
            raw.replace("9223372036854775807", "1e2"),
            raw.replace("\"AED\"", "\"USD\""),
            raw.replace("\"reply\":\"Current balance\"", "\"reply\":\"Current balance\",\"reply\":\"Other\""),
            raw.replace("\"outcome\":\"answered\"", "\"outcome\":\"success\""),
            raw.replace("\"citations\":[]", "\"citations\":[],\"owner_id\":\"other\""),
            raw.replace("\"provenance\":[{\"source\":\"wallet-service\",\"fetched_at\":\"2026-10-13T12:00:00Z\",\"scope\":\"current owner wallets\"}]", "\"provenance\":[]")
        ).forEach { assertTrue(runCatching { assistantAnswer(it) }.isFailure) }
    }
    @Test fun requestLimitsCountCodepointsAndEncodedEnvelope() {
        assertEquals("""{"message":"hello"}""", assistantRequest("hello"))
        assertTrue(runCatching { assistantRequest(" ") }.isFailure)
        assertTrue(runCatching { assistantRequest("a".repeat(2001)) }.isFailure)
        assertTrue(runCatching { assistantRequest("😀".repeat(1020)) }.isSuccess)
        assertTrue(runCatching { assistantRequest("😀".repeat(1021)) }.isFailure)
        assertTrue(runCatching { assistantRequest("\u0000".repeat(700)) }.isFailure)
        assertTrue(runCatching { assistantRequest("\uD800") }.isFailure)
    }
    @Test fun sessionSwitchLogoutAndExpiryDropPendingPersonalData() {
        val turns = AssistantTurns {}
        val ticket = turns.begin(session, "balance", now)!!
        assertNull(turns.begin(session, "another", now))
        turns.reset(null)
        turns.complete(ticket, session, assistantAnswer(raw), now)
        assertEquals(AssistantState(), turns.state)
        val next = session.copy(session = session.session.copy(access_token = "b".repeat(43)))
        val old = turns.begin(session, "balance", now)!!
        val current = turns.begin(next, "balance", now)!!
        turns.fail(old, session, HttpFailure(503, "service_unavailable"))
        assertTrue(turns.state.loading)
        turns.complete(current, next, assistantAnswer(raw), Instant.parse(next.session.expires_at))
        assertTrue(turns.state.expired); assertNull(turns.state.answer); assertNull(turns.state.question)
        turns.reset(session)
        val last = turns.begin(session, "balance", now)!!
        turns.fail(last, session, HttpFailure(401, "invalid_credentials"))
        assertTrue(turns.state.expired); assertNull(turns.state.question)
    }
    @Test fun failureRequiresExplicitNewTurnAndNeverKeepsPriorFacts() {
        val turns = AssistantTurns {}
        val first = turns.begin(session, "balance", now)!!
        turns.complete(first, session, assistantAnswer(raw), now)
        val second = turns.begin(session, "history", now)!!
        assertNull(turns.state.answer)
        turns.fail(second, session, HttpFailure(429, "server_busy"))
        assertFalse(turns.state.loading); assertNotNull(turns.state.error); assertNull(turns.state.answer)
        assertNotNull(turns.begin(session, "history", now))
    }
}
