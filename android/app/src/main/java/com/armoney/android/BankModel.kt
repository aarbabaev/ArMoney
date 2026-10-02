package com.armoney.android

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

class BankModel(application: Application) : AndroidViewModel(application) {
    val config = Configuration(BuildConfig.ORIGIN)
    val store = SecureStore(application)
    private val recovery = PaymentRecovery(config.origin, store)
    private val http = BankHttp(config)
    var session by mutableStateOf<SavedSession?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var storageBlocked by mutableStateOf(false); private set
    var wallets by mutableStateOf<List<Wallet>>(emptyList()); private set
    var balances by mutableStateOf<Map<String, Long?>>(emptyMap()); private set
    var payments by mutableStateOf<List<Payment>>(emptyList()); private set
    var notices by mutableStateOf<List<Notice>>(emptyList()); private set
    var profile by mutableStateOf<Profile?>(null); private set
    var recipient by mutableStateOf<Recipient?>(null); private set
    var pending by mutableStateOf<Pending?>(null); private set
    var detail by mutableStateOf<Payment?>(null); private set
    private var generation = 0
    private val sessionScope get() = "session|${config.origin}"
    private fun json(name: String, value: String) = JsonObject(mapOf(name to JsonPrimitive(value))).toString()
    init {
        try {
            val saved = store.read(sessionScope)?.let { wire.decodeFromString<SavedSession>(it) }
            if (saved != null) {
                require(saved.origin == config.origin); uuid(saved.identity.id)
                session = saved
            }
        } catch (_: Exception) { storageBlocked = true; message = "Secure storage is unreadable. Payments are disabled; do not send a replacement for an uncertain transfer." }
        if (session != null) refresh()
    }
    private fun task(action: suspend () -> Unit) {
        if (busy || storageBlocked) return
        busy = true; message = null
        viewModelScope.launch {
            try { action() } catch (failure: Exception) {
                message = when (failure) {
                    is HttpFailure -> if (failure.status == 401) "Session expired. Sign out and sign in again; saved transfers are preserved." else failure.message
                    is IllegalArgumentException -> failure.message ?: "The server returned an unexpected response."
                    else -> "Unable to confirm the request. Retry safely; saved transfers are preserved."
                }
            } finally { busy = false }
        }
    }
    fun reportLoginFailure() { message = "Unable to start secure browser sign-in. Check the configured HTTPS origin and browser." }
    fun acceptCallback(raw: String) = task {
        check(session == null)
        // Consumption is durable before the first network request: replay cannot exchange again.
        val (code, verifier) = AuthorizationJournal(config, store).consume(raw)
        val fields = mapOf("grant_type" to "authorization_code", "client_id" to "armoney-android", "redirect_uri" to Callback.REDIRECT, "code" to code, "code_verifier" to verifier)
        val body = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val tokens = wire.parseToJsonElement(http.send("/sso/realms/armoney/protocol/openid-connect/token", "POST", body, form = true)).jsonObject
        require(tokens["token_type"]?.jsonPrimitive?.content.equals("Bearer", true))
        val access = requireNotNull(tokens["access_token"]?.jsonPrimitive?.content).also { require(it.length in 1..8192) }
        val bank = wire.decodeFromString<Session>(http.send("/v1/auth/sso", "POST", json("access_token", access)))
        require(bank.token_type == "Bearer" && Instant.parse(bank.expires_at).isAfter(Instant.now()))
        val identity = wire.decodeFromString<Identity>(http.send("/v1/auth/me", token = bank.access_token)); uuid(identity.id)
        val saved = SavedSession(config.origin, bank, identity)
        store.write(sessionScope, wire.encodeToString(saved)); generation++; session = saved
        load(saved)
    }
    fun refresh() = task { session?.let { load(it) } }
    private suspend fun load(s: SavedSession) {
        val epoch = generation; val token = s.session.access_token
        // Re-verify the owner before selecting a durable financial command.
        val verified = wire.decodeFromString<Identity>(http.send("/v1/auth/me", token = token))
        require(verified.id == s.identity.id)
        val recovered = try { recovery.load(verified.id) }
            catch (failure: Exception) { storageBlocked = true; throw failure }
        require(recovered == null || (recovered.origin == config.origin && recovered.owner == verified.id))
        if (generation != epoch) return
        pending = recovered
        balances = emptyMap()
        val ws = wire.decodeFromString<Wallets>(http.send("/v1/wallets", token = token)).wallets
        require(ws.all { it.owner_id == verified.id }); ws.forEach { uuid(it.id) }
        if (generation != epoch) return
        wallets = ws
        val bs = mutableMapOf<String, Long?>()
        for (w in ws.filter { it.ready }) {
            bs[w.id] = runCatching {
                wire.decodeFromString<Balance>(http.send("/v1/wallets/${w.id}/balance", token = token)).also {
                    require(it.wallet_id == w.id && it.currency == w.currency && it.balance_minor >= 0)
                }.balance_minor
            }.getOrNull()
        }
        if (generation != epoch) return
        balances = bs
        val ps = wire.decodeFromString<Payments>(http.send("/v1/payments", token = token)).payments
        require(ps.size <= 100 && ps.all { it.requester_id == verified.id || (it.recipient_id == verified.id && it.status == "COMPLETED") })
        if (generation != epoch) return
        payments = ps
        val ns = wire.decodeFromString<Notices>(http.send("/v1/notifications", token = token)).notifications
        require(ns.size <= 100)
        if (generation != epoch) return
        notices = ns
        val p = try { wire.decodeFromString<Profile>(http.send("/v1/users/me", token = token)) } catch (f: HttpFailure) { if (f.status == 404) null else throw f }
        require(p == null || p.identity_id == verified.id)
        if (generation == epoch) profile = p
    }
    private fun authenticated(action: suspend (SavedSession, Int) -> Unit) = task { val s = session ?: error("Sign in first"); action(s, generation) }
    fun openWallet(currency: String) = authenticated { s, _ -> require(currency in setOf("EUR", "USD", "GBP")); http.send("/v1/wallets", "POST", json("currency", currency), s.session.access_token); load(s) }
    fun resolve(value: String) = authenticated { s, epoch ->
        recipient = null
        val p = phone(value)
        val result = wire.decodeFromString<Recipient>(http.send("/v1/recipients/resolve", "POST", json("phone_number", p), s.session.access_token))
        uuid(result.identity_id); require(result.identity_id != s.identity.id && result.phone_number == p)
        if (generation == epoch) recipient = result
    }
    fun clearRecipient() { recipient = null }
    fun submit(wallet: Wallet, amount: String) = authenticated { s, epoch ->
        check(pending == null); val r = recipient ?: error("Confirm a recipient first")
        require(wallet.owner_id == s.identity.id && wallet.ready)
        val command = PaymentCommand(uuid(wallet.id), uuid(r.identity_id), phone(r.phone_number), wallet.currency, minor(amount))
        val p = Pending(config.origin, s.identity.id, UUID.randomUUID().toString(), command)
        recovery.save(p); pending = p
        sendPending(s, p, epoch)
    }
    fun retry() = authenticated { s, epoch -> sendPending(s, pending ?: error("No saved transfer"), epoch) }
    private suspend fun sendPending(s: SavedSession, original: Pending, epoch: Int) {
        require(original.owner == s.identity.id && original.origin == config.origin)
        val result = try {
            recovery.execute(s.identity.id) { saved ->
                if (generation == epoch) pending = saved
                wire.decodeFromString<Payment>(http.send("/v1/payments", "POST", wire.encodeToString(saved.command), s.session.access_token, saved.key))
            }
        } finally {
            if (generation == epoch) pending = recovery.load(s.identity.id)
        }
        if (generation != epoch) return
        detail = result; message = "Transfer ${result.status.lowercase()}."
        load(s)
    }
    fun paymentDetails(id: String) = authenticated { s, epoch ->
        val p = wire.decodeFromString<Payment>(http.send("/v1/payments/${uuid(id)}", token = s.session.access_token))
        require(p.id == id && (p.requester_id == s.identity.id || (p.recipient_id == s.identity.id && p.status == "COMPLETED")))
        if (generation == epoch) detail = p
    }
    fun closeDetails() { detail = null }
    fun readNotice(id: String) = authenticated { s, _ -> http.send("/v1/notifications/${uuid(id)}/read", "POST", token = s.session.access_token); load(s) }
    fun saveName(value: String) = authenticated { s, _ -> require(value.trim().length in 1..100); http.send("/v1/users/me", "PUT", json("display_name", value.trim()), s.session.access_token); load(s) }
    fun savePhone(value: String) = authenticated { s, _ -> http.send("/v1/users/me/phone", "PUT", json("phone_number", phone(value)), s.session.access_token); load(s) }
    fun logout() = task {
        val s = session ?: return@task
        store.remove(sessionScope); store.remove(Login.scope(config.origin))
        generation++; session = null; pending = null; profile = null; recipient = null; wallets = emptyList(); balances = emptyMap(); payments = emptyList(); notices = emptyList(); detail = null
        try { http.send("/v1/auth/logout", "POST", token = s.session.access_token) }
        catch (_: Exception) { message = "Signed out on this device. Server revocation could not be confirmed; the session expires automatically." }
    }
}
