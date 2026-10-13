import { Component, inject } from "@angular/core";
import { RouterLink } from "@angular/router";
import { Bank } from "../core/bank";
import { PaymentList } from "./payment-list";
@Component({
  standalone: true,
  imports: [RouterLink, PaymentList],
  template: `<div class="heading">
      <div>
        <p class="eyebrow">YOUR EVERYDAY MONEY</p>
        <h1>
          Hello{{
            bank.profile()?.display_name
              ? ", " + bank.profile()?.display_name
              : ""
          }}
        </h1>
        <p>One place for your AED accounts and transfers.</p>
      </div>
      <a class="button" routerLink="/transfers">Send money ↗</a>
    </div>
    <div class="grid">
      @for (w of bank.wallets(); track w.id) {
        <a class="panel tonal account" [routerLink]="['/accounts', w.id]"
          ><span>AED account</span
          ><strong class="balance">{{
            bank.balances()[w.id] !== undefined &&
            bank.balances()[w.id] !== null
              ? bank.money(bank.balances()[w.id]!)
              : "Balance unavailable"
          }}</strong
          ><span>{{ w.status }} · {{ w.provisioning_status }}</span
          ><span>View account →</span></a
        >
      } @empty {
        <section class="panel tonal">
          <h2>Your first account</h2>
          <p>Open an AED wallet. Provisioning does not provide funding.</p>
          <button (click)="bank.openWallet()" [disabled]="bank.busy()">
            Open AED account
          </button>
        </section>
      }
      <section class="panel">
        <p class="eyebrow">QUICK ACCESS</p>
        <a class="quick" routerLink="/accounts">Accounts →</a
        ><a class="quick" routerLink="/cards">Demonstration cards →</a
        ><a class="quick" routerLink="/inbox">Inbox →</a>
      </section>
    </div>
    <section class="panel">
      <h2>Recent activity</h2>
      <app-payment-list />
    </section>`,
})
export class Home {
  bank = inject(Bank);
}
