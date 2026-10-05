package com.armoney.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RecoveryTest {
    private class Disk : PrivateStore {
        val records = mutableMapOf<String, String>()
        var failWrites = false
        override fun read(scope: String) = records[scope]
        override fun write(scope: String, value: String) { if (failWrites) throw IOException(); records[scope] = value }
        override fun remove(scope: String) { records.remove(scope) }
    }
    private val owner = "b3848dc9-a620-4be7-bd69-e594ab0ad72e"
    private val other = "88c149b3-885c-4d43-a185-84c25afdb199"
    private val draft = Pending("https://bank.example", owner, "immutable-key", PaymentCommand("347637d3-a560-43d3-acd1-4c3f36e1ad9c", other, "+12025550123", "AED", 1234))
    @Test fun timeoutThenRestartLogoutAndRefusalPreserveExactCommand() = runBlocking {
        val disk = Disk(); val first = PaymentRecovery(draft.origin, disk); first.save(draft)
        val firstError = runCatching { first.execute(owner) {
            assertTrue(PaymentRecovery(draft.origin, disk).load(owner)!!.attempted)
            assertEquals(draft.command, it.command); throw IOException("lost response")
        } }.exceptionOrNull()
        assertTrue(firstError is IOException)
        // Logout deletes only the session record; the same durable store survives recreation.
        disk.write("session|${draft.origin}", "session"); disk.remove("session|${draft.origin}")
        val restarted = PaymentRecovery(draft.origin, disk)
        assertNull(restarted.load(other)); assertNull(PaymentRecovery("https://other.example", disk).load(owner))
        assertEquals(draft.key, restarted.load(owner)!!.key)
        runCatching { restarted.execute(owner) {
            assertEquals(draft.command, it.command); assertEquals(draft.key, it.key)
            throw HttpFailure(409, "recipient_changed")
        } }
        assertTrue(restarted.load(owner)!!.attempted)
        assertTrue(runCatching { restarted.save(draft.copy(key = "replacement")) }.isFailure)
    }
    @Test fun failedAttemptMarkerPreventsNetworkAndFirstRefusalReleasesDraft() = runBlocking {
        val disk = Disk(); val recovery = PaymentRecovery(draft.origin, disk); recovery.save(draft)
        disk.failWrites = true; var sent = false
        assertTrue(runCatching { recovery.execute(owner) { sent = true; error("must not send") } }.isFailure)
        assertFalse(sent); assertFalse(recovery.load(owner)!!.attempted)
        disk.failWrites = false
        runCatching { recovery.execute(owner) { throw HttpFailure(404, "not_found") } }
        assertNull(recovery.load(owner))
    }
    @Test fun legacyUncertainCurrencyIsNeverRelabeledOrDiscarded() = runBlocking {
        val disk = Disk()
        val legacy = draft.copy(command = draft.command.copy(currency = "EUR"), attempted = true)
        // This record predates AED-only creation; load must retain its historical intent.
        disk.write("payment|${draft.origin}|$owner", kotlinx.serialization.json.Json.encodeToString(Pending.serializer(), legacy))
        val restarted = PaymentRecovery(draft.origin, disk)
        assertEquals(legacy, restarted.load(owner))
        assertEquals("EUR 12.34", money(legacy.command.amount_minor, legacy.command.currency))
        val failure = runCatching { restarted.execute(owner) { saved ->
            assertEquals(legacy, saved)
            throw HttpFailure(400, "invalid_request")
        } }.exceptionOrNull()
        assertTrue(failure is HttpFailure)
        assertEquals(legacy, PaymentRecovery(draft.origin, disk).load(owner))
        assertTrue(runCatching { restarted.save(draft) }.isFailure)
    }
    @Test fun corruptStorageNeverBecomesANewDraft() {
        val disk = Disk(); val recovery = PaymentRecovery(draft.origin, disk)
        disk.write("payment|${draft.origin}|$owner", "corrupt")
        assertTrue(runCatching { recovery.load(owner) }.isFailure)
        assertTrue(runCatching { recovery.save(draft) }.isFailure)
    }
    @Test fun malformedOrMismatchedTerminalResponsePreservesAttempt() = runBlocking {
        val disk = Disk(); val recovery = PaymentRecovery(draft.origin, disk); recovery.save(draft)
        val malformed = Payment("23771763-68c5-4e1f-8533-6ce62c708268", owner, other, draft.command.source_wallet_id,
            "045659e0-c697-4faa-9fbc-f8ba12801a5d", draft.command.recipient_phone, "AED", 1234, "REJECTED", null, "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z")
        assertTrue(runCatching { recovery.execute(owner) { malformed } }.isFailure)
        assertNotNull(recovery.load(owner))
        assertTrue(runCatching { recovery.execute(owner) { malformed.copy(status = "COMPLETED", amount_minor = 999) } }.isFailure)
        assertEquals(draft.command, recovery.load(owner)!!.command)
    }
}
