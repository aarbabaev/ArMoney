import { Component, inject, signal } from "@angular/core";
import { RouterLink, ActivatedRoute } from "@angular/router";
import { Bank } from "../core/bank";
import { PaymentList } from "./payment-list";
@Component({
  standalone: true,
  imports: [RouterLink, PaymentList],
  template: `<a routerLink="/accounts">← Accounts</a>
    <h1>Account details</h1>
    @if (wallet(); as w) {
      <section class="panel tonal">
        <h2>AED account</h2>
        <p class="balance">
          {{
            bank.balances()[w.id] !== undefined &&
            bank.balances()[w.id] !== null
              ? bank.money(bank.balances()[w.id]!)
              : "Balance unavailable"
          }}
        </p>
        <dl>
          <dt>Currency</dt>
          <dd>AED</dd>
          <dt>Lifecycle</dt>
          <dd>{{ w.status }}</dd>
          <dt>Ledger provisioning</dt>
          <dd>{{ w.provisioning_status }}</dd>
        </dl>
      </section>
      <section class="panel">
        <h2>Internal wallet identifier</h2>
        <p class="identifier">{{ w.id }}</p>
        <p>
          This identifier is not an IBAN or a bank account number. It cannot
          receive a bank transfer.
        </p>
        <button class="secondary" (click)="copy(w.id)">
          {{ copied() ? "Identifier copied" : "Copy wallet identifier" }}
        </button>
        @if (bank.ready(w) && !bank.pending() && !bank.blocked()) {
          <a
            class="button"
            routerLink="/transfers"
            [queryParams]="{ wallet: w.id }"
            >Send from this account</a
          >
        } @else {
          <p>
            Transfers require an active ready account and no unresolved saved
            transfer.
          </p>
        }
      </section>
      <section class="panel">
        <h2>Account activity</h2>
        <p>
          Filtered from loaded history. Load additional pages for older
          activity; this is not a full account statement.
        </p>
        <app-payment-list />
      </section>
    } @else {
      <p class="empty">
        This account is unavailable for your current identity.
      </p>
    }`,
})
export class Account {
  bank = inject(Bank);
  route = inject(ActivatedRoute);
  copied = signal(false);
  wallet() {
    return this.bank
      .wallets()
      .find((w) => w.id === this.route.snapshot.paramMap.get("id"));
  }
  copy(id: string) {
    void this.bank.task(async () => {
      await navigator.clipboard.writeText(id);
      this.copied.set(true);
    });
  }
}
