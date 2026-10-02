package com.armoney.android

import kotlinx.serialization.encodeToString

interface PrivateStore {
    fun read(scope: String): String?
    fun write(scope: String, value: String)
    fun remove(scope: String)
}

/** Durable client command journal. Transport tests prove ordering, not ledger success. */
class PaymentRecovery(private val origin: String, private val store: PrivateStore) {
    private fun scope(owner: String) = "payment|$origin|${uuid(owner)}"
    fun load(owner: String): Pending? = store.read(scope(owner))?.let {
        wire.decodeFromString<Pending>(it).also { p -> require(p.origin == origin && p.owner == owner) }
    }
    fun save(pending: Pending) {
        require(pending.origin == origin && !pending.attempted)
        check(load(pending.owner) == null) { "A transfer is already saved for this account." }
        store.write(scope(pending.owner), wire.encodeToString(pending))
    }
    suspend fun execute(owner: String, send: suspend (Pending) -> Payment): Payment {
        val original = load(owner) ?: error("No saved transfer")
        val attempted = original.copy(attempted = true)
        store.write(scope(owner), wire.encodeToString(attempted))
        val result = try { send(attempted) } catch (failure: Exception) {
            if (mayDiscard(original.attempted, failure)) store.remove(scope(owner))
            throw failure
        }
        require(result.matches(original)); uuid(result.id); uuid(result.destination_wallet_id)
        if (result.status in setOf("COMPLETED", "REJECTED")) store.remove(scope(owner))
        return result
    }
}
