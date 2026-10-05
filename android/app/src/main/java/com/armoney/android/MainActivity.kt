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

class MainActivity : ComponentActivity() {
    private val model: BankModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BankScreen(model) { runCatching { Login.start(this, model.store, model.config) }.onFailure { model.reportLoginFailure() } } } }
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
    val tabs = listOf("Wallets", "Transfers", "Inbox", "Profile")
    Scaffold(bottomBar = {
        if (model.session != null) NavigationBar { tabs.forEachIndexed { index, title ->
            NavigationBarItem(selected = index == tab, onClick = { tab = index; model.refresh() }, icon = { Text(listOf("◉", "↗", "✉", "●")[index]) }, label = { Text(title) })
        } }
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ArMoney", style = MaterialTheme.typography.headlineLarge)
            model.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.session == null) {
                Text("Your money, clearly.", style = MaterialTheme.typography.headlineSmall)
                Text("Sign in securely in your browser to manage wallets and send payments.")
                Button(onClick = login, enabled = !model.busy && !model.storageBlocked) { Text("Sign in with ArMoney") }
                Text("Bank server: ${model.config.origin}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(tabs[tab], style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = model::refresh, enabled = !model.busy) { Text("Refresh") }
                when (tab) { 0 -> WalletScreen(model); 1 -> TransferScreen(model); 2 -> InboxScreen(model); 3 -> ProfileScreen(model) }
            }
        }
    }
    model.detail?.let { payment ->
        AlertDialog(onDismissRequest = model::closeDetails, title = { Text("Transfer ${payment.status.lowercase()}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(money(payment.amount_minor, payment.currency)); Text("To ${payment.recipient_phone}")
                Text("Reference: ${payment.id}"); Text("Created: ${payment.created_at}"); Text("Updated: ${payment.updated_at}")
                payment.rejection_reason?.let { Text(it.replace('_', ' ')) }
                if (payment.status == "PENDING") Text("Awaiting ledger confirmation. This is not a completed payment.")
            }
        }, confirmButton = { TextButton(onClick = { model.paymentDetails(payment.id) }, enabled = !model.busy) { Text("Refresh status") } }, dismissButton = { TextButton(onClick = model::closeDetails) { Text("Close") } })
    }
}
@Composable private fun WalletScreen(model: BankModel) {
    if (model.wallets.isEmpty()) Text("No wallets loaded. Open a wallet to get started.")
    model.wallets.forEach { wallet ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(wallet.currency, style = MaterialTheme.typography.titleLarge)
            Text(model.balances[wallet.id]?.let { money(it, wallet.currency) } ?: "Balance unavailable")
            Text("${wallet.status} · ${wallet.provisioning_status}"); Text(wallet.id, style = MaterialTheme.typography.bodySmall)
        } }
    }
    OutlinedButton(onClick = { model.openWallet(SUPPORTED_CURRENCY) }, enabled = !model.busy) { Text("Open AED wallet") }
    Text("Wallets are not automatically funded. Refresh to check provisioning.", style = MaterialTheme.typography.bodySmall)
}
@Composable private fun TransferScreen(model: BankModel) {
    var phone by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf(false) }
    val pending = model.pending
    if (pending != null) {
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved transfer — confirmation required", style = MaterialTheme.typography.titleMedium)
            Text("${money(pending.command.amount_minor, pending.command.currency)} to ${pending.command.recipient_phone}")
            Text("Reference: ${pending.key}")
            Text("An earlier request may have been accepted. Retry uses the same reference and amount. Do not send a replacement.")
            Button(onClick = model::retry, enabled = !model.busy) { Text("Retry saved transfer") }
        } }
    } else {
        RecipientPhoneField(phone, { phone = it; model.clearRecipient() }, !model.busy)
        Button(onClick = { model.resolve(phone) }, enabled = !model.busy) { Text("Find recipient") }
        model.recipient?.let { recipient ->
            Text("Recipient: ${recipient.display_name} · ${recipient.phone_number}")
            Text("Choose a source wallet")
            model.wallets.filter { it.ready }.forEach { wallet ->
                Row { RadioButton(selected == wallet.id, onClick = { selected = wallet.id }, enabled = !model.busy); Text("${wallet.currency} · ${model.balances[wallet.id]?.let { money(it, wallet.currency) } ?: "balance unavailable"}", Modifier.padding(top = 12.dp)) }
            }
            OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount in AED (for example 12.34)") }, enabled = !model.busy, singleLine = true)
            Button(onClick = { confirm = true }, enabled = !model.busy && selected != null && runCatching { minor(amount) }.isSuccess) { Text("Review transfer") }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm recipient and amount") }, text = {
                Text("Send $amount ${model.wallets.find { it.id == selected }?.currency} to ${recipient.display_name}, ${recipient.phone_number}?")
            }, confirmButton = { TextButton(onClick = { confirm = false; model.wallets.find { it.id == selected }?.let { model.submit(it, amount) } }, enabled = !model.busy) { Text("Confirm and send") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        }
    }
    HorizontalDivider(); Text("Recent transfers (up to 100)", style = MaterialTheme.typography.titleMedium)
    model.payments.forEach { payment ->
        OutlinedButton(onClick = { model.paymentDetails(payment.id) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
            Column { Text("${money(payment.amount_minor, payment.currency)} · ${payment.status}"); Text("${if (payment.requester_id == model.session?.identity?.id) "To" else "Incoming from"} ${if (payment.requester_id == model.session?.identity?.id) payment.recipient_phone else payment.requester_id}") }
        }
    }
}
@Composable private fun InboxScreen(model: BankModel) {
    Text("In-app notifications refresh while you use the app. No background push delivery.")
    model.notices.forEach { notice ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(notice.type.replace('_', ' ')); Text(money(notice.amount_minor, notice.currency)); Text(notice.created_at)
            TextButton(onClick = { model.paymentDetails(notice.payment_id) }, enabled = !model.busy) { Text("View transfer") }
            if (notice.read_at == null) TextButton(onClick = { model.readNotice(notice.id) }, enabled = !model.busy) { Text("Mark read") } else Text("Read")
        } }
    }
}
@Composable private fun ProfileScreen(model: BankModel) {
    var name by remember(model.profile?.display_name) { mutableStateOf(model.profile?.display_name.orEmpty()) }
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, enabled = !model.busy)
    Button(onClick = { model.saveName(name) }, enabled = !model.busy && name.isNotBlank()) { Text("Save name") }
    RegisteredPhone(model.profile)
    HorizontalDivider(); Text("Account", style = MaterialTheme.typography.titleMedium)
    Text(model.session?.identity?.email ?: "No email supplied by your identity provider")
    Text("Identity: ${model.session?.identity?.id}")
    Button(onClick = model::logout, enabled = !model.busy) { Text("Sign out") }
    Text("Sign-out removes this device's bank session. The browser SSO session may remain active. Uncertain transfers stay saved for this account.", style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun RecipientPhoneField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    Text("United Arab Emirates · +971")
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text("Recipient mobile number") },
        prefix = { Text("+971 ") }, supportingText = { Text("9 digits, starting with 50, 52, 54, 55, 56 or 58") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true)
}

@Composable internal fun RegisteredPhone(profile: Profile?) {
    Text("Phone number")
    Text(profile?.phone_number ?: "No phone number available")
    if (profile?.phone_number != null) {
        Text(if (profile.phone_verified) "Phone verified" else "Ownership not verified")
        Text("Phone changes are unavailable here. No SMS verification is provided. Transfers do not require phone verification.")
    }
}
