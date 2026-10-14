package com.armoney.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val assistantWire = Json { ignoreUnknownKeys = false }
@Serializable data class AssistantMessage(val message: String)
@Serializable data class AssistantCitation(val document_id: String, val version: String, val section: String, val excerpt: String)
@Serializable data class AssistantWallet(val wallet_id: String, val currency: String, val balance_minor: Long)
@Serializable data class AssistantTransaction(val id: String, val created_at: String, val currency: String, val amount_minor: Long, val direction: String, val status: String)
@Serializable data class AssistantFacts(val wallets: List<AssistantWallet>?, val last_transaction: AssistantTransaction?)
@Serializable data class AssistantProvenance(val source: String, val fetched_at: String, val scope: String)
@Serializable data class AssistantAnswer(val reply: String, val outcome: String, val citations: List<AssistantCitation>, val facts: AssistantFacts, val provenance: List<AssistantProvenance>)

fun assistantRequest(message: String): String {
    require(message.isNotBlank() && message.codePointCount(0, message.length) in 1..2000) { "Enter a question of up to 2,000 characters." }
    require(message.indices.none { Character.isSurrogate(message[it]) && (Character.isHighSurrogate(message[it]) && (it + 1 == message.length || !Character.isLowSurrogate(message[it + 1])) || Character.isLowSurrogate(message[it]) && (it == 0 || !Character.isHighSurrogate(message[it - 1]))) }) { "Invalid text." }
    return assistantWire.encodeToString(AssistantMessage(message)).also {
        require(it.toByteArray(Charsets.UTF_8).size <= 4096) { "Question exceeds the 4,096 byte request limit. Shorten it." }
    }
}

fun assistantAnswer(raw: String): AssistantAnswer {
    require(raw.toByteArray(Charsets.UTF_8).size <= 32768)
    uniqueAssistantKeys(raw)
    val facts = assistantWire.parseToJsonElement(raw).jsonObject.getValue("facts").jsonObject
    fun integer(value: kotlinx.serialization.json.JsonElement) {
        val number = value.jsonPrimitive
        require(!number.isString && number.content.matches(Regex("-?(0|[1-9][0-9]*)")))
    }
    facts.getValue("wallets").takeUnless { it == JsonNull }?.jsonArray?.forEach { integer(it.jsonObject.getValue("balance_minor")) }
    facts.getValue("last_transaction").takeUnless { it == JsonNull }?.jsonObject?.let { integer(it.getValue("amount_minor")) }
    return assistantWire.decodeFromString<AssistantAnswer>(raw).also { answer ->
        fun bounded(value: String, max: Int) = require(value.isNotBlank() && value.codePointCount(0, value.length) <= max)
        bounded(answer.reply, 8000)
        require(answer.outcome in setOf("answered", "insufficient_evidence", "empty_history", "conflicting_evidence"))
        require(answer.citations.size <= 6 && answer.provenance.size <= 3)
        answer.citations.forEach { bounded(it.document_id, 128); bounded(it.version, 64); bounded(it.section, 256); bounded(it.excerpt, 1000) }
        answer.facts.wallets?.let { wallets ->
            require(wallets.size <= 10)
            require(wallets.map { it.wallet_id }.distinct().size == wallets.size)
            wallets.forEach { uuid(it.wallet_id); supportedCurrency(it.currency); require(it.balance_minor >= 0) }
        }
        answer.facts.last_transaction?.let { transaction ->
            uuid(transaction.id); supportedCurrency(transaction.currency); require(transaction.amount_minor > 0)
            Instant.parse(transaction.created_at)
            require(transaction.direction in setOf("incoming", "outgoing") && transaction.status in setOf("PENDING", "COMPLETED", "REJECTED"))
            require(transaction.direction != "incoming" || transaction.status == "COMPLETED")
        }
        require(answer.provenance.map { it.source }.distinct().size == answer.provenance.size)
        answer.provenance.forEach { require(it.source in setOf("wallet-service", "payment-service", "policy-index")); Instant.parse(it.fetched_at); bounded(it.scope, 128) }
        require(answer.facts.wallets == null || answer.provenance.any { it.source == "wallet-service" })
        require(answer.facts.last_transaction == null || answer.provenance.any { it.source == "payment-service" })
        require(answer.citations.isEmpty() || answer.provenance.any { it.source == "policy-index" })
    }
}

/** Reject duplicate keys before decoding: JSON object maps otherwise silently overwrite them. */
private fun uniqueAssistantKeys(raw: String) {
    var index = 0
    fun space() { while (index < raw.length && raw[index].isWhitespace()) index++ }
    fun string(): String {
        val start = index; require(index < raw.length && raw[index++] == '"')
        while (index < raw.length) {
            when (raw[index++]) {
                '\\' -> { require(index < raw.length); index++ }
                '"' -> return assistantWire.decodeFromString<String>(raw.substring(start, index))
            }
        }
        error("Unterminated JSON string")
    }
    fun value(depth: Int) {
        require(depth <= 12); space(); require(index < raw.length)
        when (raw[index]) {
            '{' -> {
                index++; space(); val keys = mutableSetOf<String>()
                if (index < raw.length && raw[index] == '}') { index++; return }
                while (true) {
                    space(); require(keys.add(string())); space(); require(index < raw.length && raw[index++] == ':')
                    value(depth + 1); space(); require(index < raw.length)
                    val delimiter = raw[index++]; if (delimiter == '}') break; require(delimiter == ',')
                }
            }
            '[' -> {
                index++; space()
                if (index < raw.length && raw[index] == ']') { index++; return }
                while (true) {
                    value(depth + 1); space(); require(index < raw.length)
                    val delimiter = raw[index++]; if (delimiter == ']') break; require(delimiter == ',')
                }
            }
            '"' -> string()
            else -> { val start = index; while (index < raw.length && raw[index] !in ",]}" && !raw[index].isWhitespace()) index++; require(index > start) }
        }
    }
    value(0); space(); require(index == raw.length)
}

fun assistantTime(value: String): String = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss 'Asia/Dubai'", java.util.Locale.ENGLISH).withZone(ZoneId.of("Asia/Dubai")).format(Instant.parse(value))

data class AssistantState(val loading: Boolean = false, val question: String? = null, val answer: AssistantAnswer? = null, val error: String? = null, val expired: Boolean = false)

/** Volatile one-turn state. Request tickets bind results to the exact bank session and origin. */
class AssistantTurns(private val changed: (AssistantState) -> Unit) {
    private var scope: SavedSession? = null
    private var generation = 0L
    private var active: Long? = null
    var state = AssistantState(); private set
    private fun publish(value: AssistantState) { state = value; changed(value) }
    fun reset(session: SavedSession?) { scope = session; generation++; active = null; publish(AssistantState()) }
    fun begin(session: SavedSession, question: String, now: Instant = Instant.now()): Long? {
        if (scope != session) reset(session)
        if (!Instant.parse(session.session.expires_at).isAfter(now)) { expire(); return null }
        if (active != null || state.expired) return null
        assistantRequest(question)
        val ticket = ++generation; active = ticket
        publish(AssistantState(loading = true, question = question)); return ticket
    }
    fun current(ticket: Long, session: SavedSession) = active == ticket && scope == session
    fun complete(ticket: Long, session: SavedSession, answer: AssistantAnswer, now: Instant = Instant.now()) {
        if (!current(ticket, session)) return
        if (!Instant.parse(session.session.expires_at).isAfter(now)) { expire(); return }
        active = null; publish(state.copy(loading = false, answer = answer))
    }
    fun fail(ticket: Long, session: SavedSession, failure: Exception) {
        if (!current(ticket, session)) return
        if (failure is HttpFailure && failure.status == 401) { expire(); return }
        active = null
        val message = when {
            failure is HttpFailure && failure.status == 429 -> "Assistant is busy. Submit again when you are ready."
            failure is HttpFailure && failure.status == 413 -> "Question is too large. Shorten it and submit again."
            failure is HttpFailure && failure.status == 400 -> "The question could not be accepted. Edit it and submit again."
            else -> "Assistant unavailable. No answer or live data could be confirmed. Submit again when you are ready."
        }
        publish(state.copy(loading = false, answer = null, error = message))
    }
    fun expire() { generation++; active = null; publish(AssistantState(expired = true, error = "Session expired. Sign out and sign in again.")) }
}
