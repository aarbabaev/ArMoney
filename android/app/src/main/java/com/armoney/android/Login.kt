package com.armoney.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlinx.serialization.encodeToString

object Login {
    fun scope(origin: String) = "oauth|$origin"
    fun request(config: Configuration): AuthorizationRequest {
        val service = AuthorizationServiceConfiguration(Uri.parse(config.oidc("auth")), Uri.parse(config.oidc("token")))
        return AuthorizationRequest.Builder(service, "armoney-android", ResponseTypeValues.CODE, Uri.parse(Callback.REDIRECT))
            .setScope("openid profile email").build()
    }
    fun start(activity: Activity, store: SecureStore, config: Configuration) {
        config.usable()
        val request = request(config)
        check(request.codeVerifierChallengeMethod == "S256")
        val pending = OAuthPending(requireNotNull(request.state), requireNotNull(request.codeVerifier), System.currentTimeMillis())
        store.write(scope(config.origin), wire.encodeToString(pending))
        CustomTabsIntent.Builder().build().launchUrl(activity, request.toUri())
    }
}

/** This narrow exported entry point never accepts bank credentials from an intent. */
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.dataString
        if (raw != null && runCatching { java.net.URI(raw).let { it.rawFragment == null && raw.substringBefore('?') == Callback.REDIRECT } }.getOrDefault(false)) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("oauth_callback", raw)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
