package com.armoney.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable internal fun AssistantScreen(model: BankModel) {
    val session = model.session
    val state = model.assistant
    var question by remember(session, state.expired) { mutableStateOf("") }
    Text("Ask about your wallets, recent activity or demonstration policies.")
    Text("Each question stands alone. Previous questions are not sent or saved. The assistant cannot make transfers.", style = MaterialTheme.typography.bodySmall)
    val validation = runCatching { assistantRequest(question) }.exceptionOrNull()?.message
    OutlinedTextField(value = question, onValueChange = { question = it }, label = { Text("Your question") },
        supportingText = { Text(if (question.isEmpty()) "Up to 2,000 characters; 4,096 bytes including the request." else validation ?: "Ready to send") },
        isError = question.isNotEmpty() && validation != null, enabled = !state.loading && !state.expired,
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
    PrimaryAction(onClick = { model.askAssistant(question) }, enabled = session != null && !state.loading && !state.expired && validation == null) { Text("Ask assistant") }
    if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking sources…") }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!state.loading && state.answer == null && state.error == null) Text("No answer yet. Submit a question to begin.")
    state.answer?.let { answer ->
        BankCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(BankDesign.Page), verticalArrangement = Arrangement.spacedBy(BankDesign.Gap)) {
            state.question?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(when (answer.outcome) {
                "empty_history" -> "No matching transaction history"
                "insufficient_evidence" -> "Insufficient evidence"
                "conflicting_evidence" -> "Conflicting sources"
                else -> "Answer"
            }, style = MaterialTheme.typography.titleMedium)
            Text(answer.reply)
        } }
        Text("Live account facts", style = MaterialTheme.typography.titleMedium)
        if (answer.facts.wallets == null) Text("Wallet balances were not supplied.")
        else if (answer.facts.wallets.isEmpty()) Text("No wallets returned by this lookup.")
        else answer.facts.wallets.forEach { Text("${it.wallet_id}: ${money(it.balance_minor, it.currency)}") }
        answer.facts.last_transaction?.let {
            Text("Last transaction: ${it.id}")
            Text("${it.direction} · ${money(it.amount_minor, it.currency)} · ${it.status}")
            Text(assistantTime(it.created_at))
            if (it.status == "PENDING") Text("Awaiting ledger confirmation.")
        } ?: Text("No last transaction was supplied; this does not establish that all history is empty.")
        answer.provenance.filter { it.source != "policy-index" }.forEach { AssistantSource(it) }
        HorizontalDivider()
        Text("Policy sources — demonstration rules", style = MaterialTheme.typography.titleMedium)
        Text("These are demonstration policies, not official bank terms.", style = MaterialTheme.typography.bodySmall)
        if (answer.citations.isEmpty()) Text("No policy citations supplied.")
        answer.citations.forEach { citation ->
            BankCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(BankDesign.Page), verticalArrangement = Arrangement.spacedBy(BankDesign.Gap)) {
                Text("${citation.document_id} · version ${citation.version}", style = MaterialTheme.typography.titleSmall)
                Text(citation.section); Text(citation.excerpt)
            } }
        }
        answer.provenance.filter { it.source == "policy-index" }.forEach { AssistantSource(it) }
    }
}

@Composable private fun AssistantSource(provenance: AssistantProvenance) {
    val label = when (provenance.source) {
        "wallet-service" -> "Live account balances"
        "payment-service" -> "Live transaction history"
        else -> "Demonstration policies"
    }
    Text("$label · Fetched ${assistantTime(provenance.fetched_at)}", style = MaterialTheme.typography.bodySmall)
    if (provenance.source == "payment-service") {
        Text("Latest visible payment by creation time, within the first 50 records. This is a limited recent-history lookup.", style = MaterialTheme.typography.bodySmall)
    }
}
