import { Component, inject } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Bank } from "../core/bank";
import { FormReadiness } from "../core/form-readiness";
import { assistantTime } from "../core/assistant";
@Component({
  standalone: true,
  imports: [FormsModule, FormReadiness],
  template: `<h1>Assistant</h1>
    <section class="panel tonal">
      <h2>Ask about your bank</h2>
      <p>Read-only help with demonstration rules, balances and your last visible transaction. The assistant cannot send money.</p>
      <p>Each question is independent. Only the current answer is kept in memory until sign-out or reload. Minimized questions and policy excerpts may reach the configured OpenAI provider; provider retention may apply. Avoid entering passwords or sensitive personal details.</p>
      <form (ngSubmit)="send()">
        <fieldset disabled armoneyFormReady class="form-fields">
        <label for="assistant-question">Your question</label>
        <textarea id="assistant-question" name="question" [(ngModel)]="question" rows="3" required [disabled]="bank.assistant().loading"></textarea>
        <button [disabled]="bank.assistant().loading || !question.trim()">{{ bank.assistant().loading ? 'Asking…' : 'Send question' }}</button>
        </fieldset>
      </form>
      @if (bank.assistant().loading) { <p role="status">Checking available evidence…</p> }
      @if (bank.assistant().error) { <p role="alert">{{ bank.assistant().error }}</p> }
    </section>
    @if (bank.assistant().answer; as answer) {
      <section class="panel" aria-label="Assistant answer" aria-live="polite">
        <h2>{{ labels[answer.outcome] }}</h2>
        <p class="assistant-text">{{ answer.reply }}</p>
        @if (answer.facts.wallets; as wallets) {
          <h3>Wallet balances</h3>
          @for (wallet of wallets; track wallet.wallet_id) {
            <p><span class="identifier">{{ wallet.wallet_id }}</span> · {{ bank.money(wallet.balance_minor) }}</p>
          }
        }
        @if (answer.facts.last_transaction; as t) {
          <h3>Last visible transaction</h3>
          <p>{{ bank.money(t.amount_minor) }} · {{ t.direction }} · {{ t.status }}</p>
          <p class="identifier">{{ t.id }}</p><p>{{ time(t.created_at) }}</p>
          <p>Ordered by creation time and identifier, not completion time. History reads are limited to the latest visible page of 50 payments.</p>
        }
        @if (answer.citations.length) {
          <h3>Demonstration policy citations</h3>
          <p>These describe implemented ArMoney behavior; they are not legal bank policy.</p>
          @for (citation of answer.citations; track $index) {
            <details><summary>{{ citation.document_id }} · {{ citation.version }} · {{ citation.section }}</summary><blockquote class="assistant-text">{{ citation.excerpt }}</blockquote></details>
          }
        }
        @for (source of answer.provenance; track source.source) {
          <p>{{ source.source }} · {{ time(source.fetched_at) }}<br />Scope: {{ source.scope }}</p>
        }
      </section>
    }`,
  styles: `.assistant-text { white-space: pre-wrap; overflow-wrap: anywhere; } textarea { width: 100%; max-width: 100%; font: inherit; margin-block: .5rem 1rem; }`,
})
export class Assistant {
  readonly bank = inject(Bank);
  question = "";
  time = assistantTime;
  readonly labels = { answered: "Answer", insufficient_evidence: "Insufficient evidence", empty_history: "No visible transactions", conflicting_evidence: "Conflicting evidence" };
  send() { return this.bank.askAssistant(this.question); }
}
