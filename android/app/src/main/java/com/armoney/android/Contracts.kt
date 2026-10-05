package com.armoney.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.math.BigDecimal
import java.util.UUID

val wire = Json { ignoreUnknownKeys = true }
const val SUPPORTED_CURRENCY = "AED"
fun supportedCurrency(value: String): String = value.also { require(it == SUPPORTED_CURRENCY) { "Only AED is supported for new wallets and transfers." } }
fun uuid(value: String): String = value.also { require(UUID.fromString(it).toString() == it) }
fun phone(value: String): String = value.also { require(it.matches(Regex("\\+[1-9][0-9]{7,14}"))) { "Use an international number, such as +12025550123." } }
fun minor(value: String): Long {
    require(value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "Enter a positive amount with at most two decimals." }
    return BigDecimal(value).movePointRight(2).longValueExact().also { require(it > 0) }
}
fun money(value: Long, currency: String) = "$currency ${BigDecimal.valueOf(value, 2).toPlainString()}"
data class Configuration(val origin: String) {
    init {
        val u = URI(origin)
        require(u.scheme == "https" && !u.host.isNullOrEmpty() && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && u.rawPath.isNullOrEmpty())
    }
    val issuer = "$origin/sso/realms/armoney"
    fun oidc(endpoint: String) = "$issuer/protocol/openid-connect/$endpoint"
    fun usable() = require(!URI(origin).host.endsWith(".invalid")) { "Configure armoney.origin in android/local.properties before signing in." }
}
object Callback {
    const val REDIRECT = "com.armoney.android:/oauth/callback"
    fun code(raw: String, state: String, issuer: String): String {
        val u = URI(raw)
        require(u.rawFragment == null && raw.substringBefore('?') == REDIRECT)
        val values = linkedMapOf<String, String>()
        (u.rawQuery ?: error("Missing callback")).split('&').forEach {
            val parts = it.split('=', limit = 2); require(parts.size == 2)
            val name = URLDecoder.decode(parts[0], "UTF-8")
            require(values.put(name, URLDecoder.decode(parts[1], "UTF-8")) == null)
        }
        require(values["state"] == state && values["error"] == null)
        require(values["iss"] == null || values["iss"] == issuer)
        return requireNotNull(values["code"]).also { require(it.isNotBlank()) }
    }
}
@Serializable data class Session(val access_token: String, val token_type: String, val expires_at: String)
@Serializable data class Identity(val id: String, val email: String? = null)
@Serializable data class SavedSession(val origin: String, val session: Session, val identity: Identity)
@Serializable data class Wallet(val id: String, val owner_id: String, val currency: String, val status: String, val provisioning_status: String) { val ready get() = currency == SUPPORTED_CURRENCY && status == "ACTIVE" && provisioning_status == "READY" }
@Serializable data class Wallets(val wallets: List<Wallet>)
@Serializable data class Balance(val wallet_id: String, val currency: String, val balance_minor: Long)
@Serializable data class Profile(val identity_id: String, val display_name: String, val phone_number: String? = null, val phone_verified: Boolean)
@Serializable data class Recipient(val identity_id: String, val display_name: String, val phone_number: String)
@Serializable data class PaymentCommand(val source_wallet_id: String, val recipient_id: String, val recipient_phone: String, val currency: String, val amount_minor: Long)
@Serializable data class Pending(val origin: String, val owner: String, val key: String, val command: PaymentCommand, val attempted: Boolean = false)
@Serializable data class Payment(val id: String, val requester_id: String, val recipient_id: String, val source_wallet_id: String, val destination_wallet_id: String, val recipient_phone: String, val currency: String, val amount_minor: Long, val status: String, val rejection_reason: String? = null, val created_at: String, val updated_at: String) {
    fun matches(p: Pending) = requester_id == p.owner && recipient_id == p.command.recipient_id && source_wallet_id == p.command.source_wallet_id && recipient_phone == p.command.recipient_phone && currency == p.command.currency && amount_minor == p.command.amount_minor && when (status) {
        "PENDING", "COMPLETED" -> rejection_reason == null
        "REJECTED" -> rejection_reason in setOf("INSUFFICIENT_FUNDS", "INVALID_ACCOUNT", "BALANCE_LIMIT")
        else -> false
    }
}
@Serializable data class Payments(val payments: List<Payment>)
@Serializable data class Notice(val id: String, val payment_id: String, val type: String, val currency: String, val amount_minor: Long, val created_at: String, val read_at: String? = null)
@Serializable data class Notices(val notifications: List<Notice>)
@Serializable data class OAuthPending(val state: String, val verifier: String, val created: Long)
class HttpFailure(val status: Int, val code: String?) : Exception("Request failed (HTTP $status). Please retry.") {
    val refused get() = (status == 400 && code in setOf("invalid_request", "request_rejected")) || (status == 404 && code == "not_found") || (status == 409 && code in setOf("recipient_changed", "wallet_ineligible"))
}
fun mayDiscard(priorAttempt: Boolean, failure: Throwable) = !priorAttempt && failure is HttpFailure && failure.refused
