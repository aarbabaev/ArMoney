package com.armoney.android

class AuthorizationJournal(private val config: Configuration, private val store: PrivateStore) {
    fun consume(raw: String, now: Long = System.currentTimeMillis()): Pair<String, String> {
        val value = store.read("oauth|${config.origin}") ?: error("Unsolicited or replayed callback")
        val request = wire.decodeFromString<OAuthPending>(value)
        require(now - request.created in 0..600_000)
        val code = Callback.code(raw, request.state, config.issuer)
        store.remove("oauth|${config.origin}")
        return code to request.verifier
    }
}
