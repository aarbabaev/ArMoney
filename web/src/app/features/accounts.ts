import { Component, inject } from "@angular/core";
import { RouterLink } from "@angular/router";
import { Bank } from "../core/bank";
@Component({
  standalone: true,
  imports: [RouterLink],
  template: `<div class="heading">
      <div>
        <h1>Accounts</h1>
        <p>Your wallet balances come from the ledger.</p>
      </div>
      <button [disabled]="bank.busy()" (click)="bank.openWallet()">
        Open AED account
      </button>
    </div>
    <div class="grid">
      @for (w of bank.wallets(); track w.id) {
        <a class="panel tonal account" [routerLink]="['/accounts', w.id]"
          ><h2>AED account</h2>
          <strong class="balance">{{
            bank.balances()[w.id] !== undefined &&
            bank.balances()[w.id] !== null
              ? bank.money(bank.balances()[w.id]!)
              : "Balance unavailable"
          }}</strong
          ><span>{{ w.status }} · {{ w.provisioning_status }}</span
          ><span class="identifier">{{ w.id }}</span></a
        >
      } @empty {
        <p class="empty">No accounts yet.</p>
      }
    </div>`,
})
export class Accounts {
  bank = inject(Bank);
}
