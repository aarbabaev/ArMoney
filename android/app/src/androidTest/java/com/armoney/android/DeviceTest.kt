package com.armoney.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlinx.serialization.encodeToString

class DeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeSignInScreen() { compose.onNodeWithText("Sign in with ArMoney").assertIsDisplayed() }
    @Test fun actualKeystorePersistsAndSeparatesOwnersAndOrigins() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test|${UUID.randomUUID()}"
        val first = SecureStore(context)
        try {
            first.write("$scope|https://one|owner-a", "immutable-payment-key-and-payload")
            val afterRestart = SecureStore(context)
            assertEquals("immutable-payment-key-and-payload", afterRestart.read("$scope|https://one|owner-a"))
            assertNull(afterRestart.read("$scope|https://one|owner-b"))
            assertNull(afterRestart.read("$scope|https://two|owner-a"))
            first.write("$scope|session", "opaque-session")
            first.remove("$scope|session")
            assertNotNull(afterRestart.read("$scope|https://one|owner-a"))
        } finally { first.remove("$scope|https://one|owner-a"); first.remove("$scope|session") }
    }
    @Test fun appAuthPkceAndEncryptedAuthorizationRecreationAreOnceOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration("https://oauth-${UUID.randomUUID()}.example")
        val request = Login.request(config)
        assertEquals("S256", request.codeVerifierChallengeMethod)
        assertTrue(requireNotNull(request.codeVerifier).length >= 43)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(requireNotNull(request.codeVerifier).toByteArray(Charsets.US_ASCII))
        val challenge = android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        assertEquals(challenge, request.codeVerifierChallenge)
        assertEquals(Callback.REDIRECT, request.redirectUri.toString())
        val first = SecureStore(context)
        first.write(Login.scope(config.origin), wire.encodeToString(OAuthPending(requireNotNull(request.state), requireNotNull(request.codeVerifier), 1000)))
        try {
            val recreated = AuthorizationJournal(config, SecureStore(context))
            assertTrue(runCatching { recreated.consume("${Callback.REDIRECT}?code=code&state=wrong", 2000) }.isFailure)
            assertNotNull(first.read(Login.scope(config.origin)))
            val result = recreated.consume("${Callback.REDIRECT}?code=code&state=${request.state}", 2000)
            assertEquals("code", result.first); assertEquals(request.codeVerifier, result.second)
            // A token exchange may begin only after consume returns and storage is empty.
            assertNull(SecureStore(context).read(Login.scope(config.origin)))
            assertTrue(runCatching { recreated.consume("${Callback.REDIRECT}?code=code&state=${request.state}", 2000) }.isFailure)
        } finally { first.remove(Login.scope(config.origin)) }
    }
    @Test fun authenticatedStorageRejectsTamperedCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "tamper-test|${UUID.randomUUID()}"
        val store = SecureStore(context)
        store.write(scope, "saved command")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = java.io.File(context.noBackupFilesDir, "armoney/$hash")
        try {
            val data = file.readBytes(); data[data.lastIndex] = (data.last().toInt() xor 1).toByte(); file.writeBytes(data)
            assertTrue(runCatching { SecureStore(context).read(scope) }.isFailure)
        } finally { store.remove(scope) }
    }
}
