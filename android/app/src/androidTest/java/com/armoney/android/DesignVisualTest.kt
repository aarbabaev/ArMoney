package com.armoney.android

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test

/** Read-only visual fixtures: no bank session is loaded, persisted or sent over HTTP. */
class DesignVisualTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val owner = "00000000-0000-0000-0000-000000000001"
    private val recipient = "00000000-0000-0000-0000-000000000002"
    private val wallet = Wallet("00000000-0000-0000-0000-000000000003", owner, "AED", "ACTIVE", "READY")
    private val payment = Payment("00000000-0000-0000-0000-000000000004", owner, recipient,
        wallet.id, "00000000-0000-0000-0000-000000000005", "+971500000000", "AED", 1234,
        "PENDING", created_at = "2026-10-31T00:00:00Z", updated_at = "2026-10-31T00:00:00Z")

    // Fail loudly if a model property changes. Reflection stays in androidTest; no test API in production.
    @Suppress("UNCHECKED_CAST")
    private fun state(model: BankModel, name: String, value: Any?) {
        val field = BankModel::class.java.getDeclaredField(name + "\$delegate")
        field.isAccessible = true
        (field.get(model) as MutableState<Any?>).value = value
    }

    @Test fun actualScreensBothThemesAndLargeTextWithoutFinancialRequests() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "visual-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val app = object : Application() { override fun getNoBackupFilesDir(): File = directory }
        lateinit var model: BankModel
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        val profileOnly = InstrumentationRegistry.getArguments().getString("profileOnly") == "true"
        compose.runOnUiThread { model = BankModel(app) }
        try {
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    ArMoneyTheme(dark) {
                        Surface { Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                            Text("SYNTHETIC UI FIXTURE · NO LIVE ACCOUNT", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge)
                            Box(Modifier.weight(1f)) { BankScreen(model) { error("Fixture must not start login") } }
                        } }
                    }
                }
            }
            for ((theme, factor) in listOf("light" to 1f, "dark" to 1f, "large-dark" to 1.5f)) {
                compose.runOnIdle {
                    dark = theme != "light"; scale = factor
                    state(model, "session", null)
                    state(model, "message", null)
                    state(model, "detail", null)
                }
                compose.onNodeWithText("Sign in with ArMoney").assertExists()
                if (!profileOnly) capture("$theme-signin")
                compose.runOnIdle {
                    state(model, "session", SavedSession(model.config.origin,
                        Session("synthetic-not-a-bank-token", "Bearer", "2099-01-01T00:00:00Z"), Identity(owner)))
                    state(model, "wallets", listOf(wallet))
                    state(model, "balances", emptyMap<String, Long?>())
                    state(model, "profile", Profile(owner, "Synthetic preview", "+971500000000", false))
                    state(model, "paymentPage", PageState(items = listOf(payment), loaded = true))
                    state(model, "noticePage", PageState(items = listOf(Notice("synthetic-notice", payment.id,
                        "PAYMENT_REJECTED", "AED", 1234, payment.created_at)), loaded = true))
                }
                for (tab in if (profileOnly) listOf("Profile") else listOf("Wallets", "Transfers", "Inbox", "Profile", "Assistant")) {
                    compose.onAllNodesWithText(tab).filter(hasClickAction()).onFirst()
                        .assertHeightIsAtLeast(48.dp).performClick()
                    compose.waitForIdle()
                    compose.onNodeWithText("ArMoney").performScrollTo()
                    capture("$theme-${tab.lowercase()}-top")
                    compose.onRoot().performTouchInput { swipeUp() }
                    capture("$theme-${tab.lowercase()}-scrolled")
                    compose.onRoot().performTouchInput { swipeDown() }
                }
                if (profileOnly) continue
                compose.onAllNodesWithText("Transfers").onLast().performClick()
                compose.runOnIdle { state(model, "recipient", Recipient(recipient, "Synthetic recipient", payment.recipient_phone)) }
                compose.onNodeWithText("Amount in AED (for example 12.34)").performScrollTo().performTextInput("12.34")
                compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                    .performScrollTo().performClick()
                compose.onNodeWithText("Review transfer").performScrollTo().assertIsEnabled()
                capture("$theme-recipient-review")
                compose.onNodeWithText("Review transfer").performClick()
                compose.onNodeWithText("Confirm recipient and amount").assertExists()
                capture("$theme-transfer-confirmation")
                // Cancel is local Compose state only; never activate Confirm and send.
                compose.onNodeWithText("Cancel").performClick()
                compose.runOnIdle {
                    state(model, "recipient", null)
                    state(model, "pending", Pending(model.config.origin, owner, "synthetic-key",
                        PaymentCommand(wallet.id, recipient, payment.recipient_phone, "AED", 1234), true))
                }
                capture("$theme-saved-uncertain")
                compose.runOnIdle { state(model, "detail", payment) }
                compose.onNodeWithText("Awaiting ledger confirmation. This is not a completed payment.").assertExists()
                capture("$theme-pending-details")
                compose.runOnIdle {
                    state(model, "detail", null); state(model, "pending", null)
                    state(model, "message", "Synthetic unavailable state. Retry safely; saved transfers are preserved.")
                    state(model, "busy", true)
                }
                capture("$theme-loading-error")
                compose.runOnIdle {
                    state(model, "busy", false); state(model, "message", null)
                    state(model, "wallets", emptyList<Wallet>())
                    state(model, "paymentPage", PageState<Payment>(loaded = true))
                    state(model, "noticePage", PageState<Notice>(loaded = true))
                }
                for (tab in listOf("Wallets", "Transfers", "Inbox")) {
                    compose.onAllNodesWithText(tab).onLast().performClick()
                    capture("$theme-${tab.lowercase()}-empty")
                }
            }
        } finally {
            // Only this test's newly created cache directory; real no-backup storage is untouched.
            directory.walkBottomUp().forEach { check(it.delete()) }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Platform window/ripple animations run outside Compose's virtual test clock.
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "design-visual").apply { mkdirs() }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
}
