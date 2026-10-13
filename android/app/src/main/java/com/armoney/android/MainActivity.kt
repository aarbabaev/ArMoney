package com.armoney.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*

class MainActivity : ComponentActivity() {
    private val model: BankModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ArMoneyTheme { BankScreen(model) { runCatching { Login.start(this, model.store, model.config) }.onFailure { model.reportLoginFailure() } } } }
        consume(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); consume(intent) }
    private fun consume(intent: Intent) {
        intent.getStringExtra("oauth_callback")?.let { intent.removeExtra("oauth_callback"); model.acceptCallback(it) }
    }
    override fun onResume() { super.onResume(); model.refresh() }
}

@Composable fun BankScreen(model: BankModel, login: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Wallets", "Transfers", "Inbox", "Profile", "Assistant")
    Scaffold(bottomBar = {
        if (model.session != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { tabs.forEachIndexed { index, title ->
            NavigationBarItem(selected = index == tab, onClick = { tab = index }, icon = { Text(listOf("◉", "↗", "✉", "●", "?")[index], Modifier.clearAndSetSemantics { }) }, label = { Text(title) })
        } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().padding(horizontal = BankDesign.Page).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(BankDesign.Space)) {
            Spacer(Modifier.height(BankDesign.Gap))
            Text("ArMoney", style = MaterialTheme.typography.titleLarge)
            model.message?.let { Surface…4293 tokens truncated…fun getInputStream() = ByteArrayInputStream(response)
        override fun getErrorStream() = ByteArrayInputStream(response)
        override fun getOutputStream() = sent
    }
    @Test fun credentialsAndImmutableCommandStayOnConfiguredOrigin() = runBlocking {
        val connection = Connection()
        var destination = ""
        val http = BankHttp(Configuration("https://bank.example")) { destination = it.toString(); connection }
        val payload = """{"amount_minor":9223372036854775807}"""
        http.send("/v1/payments", "POST", payload, "a".repeat(43), "stable-key")
        assertEquals("https://bank.example/v1/payments", destination)
        assertEquals("Bearer " + "a".repeat(43), connection.getRequestProperty("Authorization"))
        assertEquals("stable-key", connection.getRequestProperty("Idempotency-Key"))
        assertEquals(payload, connection.sent.toString("UTF-8"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(10_000, connection.connectTimeout); assertEquals(15_000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }
    @Test fun redirectsErrorsAndOversizedResponsesCannotLookSuccessful() = runBlocking {
        val redirect = Connection(302)
        val redirectError = runCatching { BankHttp(Configuration("https://bank.example")) { redirect }.send("/v1/auth/me") }.exceptionOrNull()
        assertEquals(302, (redirectError as HttpFailure).status)
        val refusal = Connection(409, """{"error":"recipient_changed"}""".toByteArray())
        val error = runCatching { BankHttp(Configuration("https://bank.example")) { refusal }.send("/v1/payments") }.exceptionOrNull()
        assertEquals("recipient_changed", (error as HttpFailure).code)
        val large = Connection(200, ByteArray(1_048_577))
        assertTrue(runCatching { BankHttp(Configuration("https://bank.example")) { large }.send("/v1/wallets") }.isFailure)
        assertTrue(large.disconnected)
    }
    @Test fun emptyReadBodyIsNotAnInventedJsonObject() = runBlocking {
        val connection = Connection()
        BankHttp(Configuration("https://bank.example")) { connection }.send("/v1/notifications/123/read", "POST", token = "a".repeat(43))
        assertEquals(0, connection.sent.size()); assertFalse(connection.doOutput)
    }
    @Test fun onlyStrictJsonRefusalCanReleaseFirstAttempt() {
        val good = """{"error":"recipient_changed"}"""
        assertEquals("recipient_changed", strictErrorCode("application/json; charset=utf-8", good, good.length))
        listOf("""{"error":"idempotency_conflict","error":"recipient_changed"}""", """{"error":"recipient_changed","extra":true}""", """{"error":409}""").forEach {
            assertNull(strictErrorCode("application/json", it, it.length))
        }
        assertNull(strictErrorCode("text/html", good, good.length))
        assertNull(strictErrorCode(null, good, good.length))
        assertNull(strictErrorCode("application/json", good, 4097))
    }
    @Test fun assistantHasSeparateTimeoutAndBoundedOneShotRequest() = runBlocking {
        val connection = Connection()
        var calls = 0
        val http = BankHttp(Configuration("https://bank.example")) { calls++; connection }
        http.send("/v1/assistant/messages", "POST", assistantRequest("balance"), "a".repeat(43))
        assertEquals(1, calls); assertEquals(40_000, connection.readTimeout)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("""{"message":"balance"}""", connection.sent.toString("UTF-8"))
        assertTrue(runCatching { BankHttp(Configuration("https://bank.example")) { Connection(200, ByteArray(32769)) }.send("/v1/assistant/messages", "POST", assistantRequest("balance"), "a".repeat(43)) }.isFailure)
        val busy = Connection(429, """{"error":"server_busy"}""".toByteArray())
        calls = 0
        val failure = runCatching { BankHttp(Configuration("https://bank.example")) { calls++; busy }.send("/v1/assistant/messages", "POST", assistantRequest("balance"), "a".repeat(43)) }.exceptionOrNull()
        assertEquals(429, (failure as HttpFailure).status); assertEquals(1, calls)
    }
}

