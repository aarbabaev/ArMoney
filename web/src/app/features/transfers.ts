import { Component, inject } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { FormsModule } from "@angular/forms";
import { Bank } from "../core/bank";
import { Command, minor, ready } from "../core/contracts";
import { PaymentList } from "./payment-list";
@Component({
  standalone: true,
  imports: [FormsModule, PaymentList],
  template: `<h1>Transfers</h1>
    <p>Send AED to a registered UAE mobile number.</p>
    @if (bank.pending(); as p) {
      <section class="panel recovery" aria-labelledby="saved">
        <h2 id="saved">Saved transfer requires confirmation</h2>
        <p>
          {{ bank.money(p.command.amount_minor) }} to
          {{ p.command.recipient_phone }}
        </p>
        <p>
          Its result may be uncertain. Retry the exact saved command; do not
          send a replacement. This record survives sign-out and refresh.
        </p>
        <button
          [disabled]="bank.busy() || bank.blocked()"
          (click)="bank.send()"
        >
          Retry saved transfer safely
        </button>
      </section>
    } @else {
      <form class="panel" (ngSubmit)="review()">
        <label for="source">Source account</label
        ><select
          id="source"
          name="source"
          [(ngModel)]="source"
          (ngModelChange)="confirmation = null"
          [disabled]="bank.busy() || bank.blocked()"
        >
          @for (w of bank.wallets(); track w.id) {
            @if (bank.ready(w)) {
              <option [value]="w.id">AED · {{ w.id }}</option>
            }
          }</select
        ><label for="phone">Recipient UAE mobile number</label>
        <div class="field-row">
          <input
            id="phone"
            name="phone"
            type="tel"
            autocomplete="tel"
            placeholder="+971501234567"
            [(ngModel)]="phone"
            (ngModelChange)="clear()"
            [disabled]="bank.busy() || bank.blocked()"
          /><button
            type="button"
            class="secondary"
            [disabled]="bank.busy() || bank.blocked()"
            (click)="bank.resolve(phone)"
          >
            Find recipient
          </button>
        </div>
        @if (bank.recipient(); as r) {
          <p class="tonal recipient">
            Recipient: <strong>{{ r.display_name }}</strong> ·
            {{ r.phone_number }}
          </p>
        }
        <label for="amount">Amount (AED)</label
        ><input
          id="amount"
          name="amount"
          inputmode="decimal"
          placeholder="0.00"
          [(ngModel)]="amount"
          (ngModelChange)="confirmation = null"
          [disabled]="bank.busy() || bank.blocked()"
        /><button
          [disabled]="!bank.recipient() || bank.busy() || bank.blocked()"
        >
          Review transfer
        </button>
      </form>
      @if (confirmation; as c) {
        <section class="panel tonal" aria-labelledby="review">
          <h2 id="review">Review and confirm</h2>
          <p>
            Send <strong>{{ bank.money(c.amount_minor) }}</strong> to
            {{ bank.recipient()?.display_name }} ({{ c.recipient_phone }}).
          </p>
          <p>
            Source wallet:
            <span class="identifier">{{ c.source_wallet_id }}</span>
          </p>
          <button
            [disabled]="bank.busy() || bank.blocked()"
            (click)="confirm()"
          >
            Confirm transfer</button
          ><button class="secondary" (click)="confirmation = null">
            Cancel
          </button>
        </section>
      }
    }
    <section class="panel">
      <h2>Transfer history</h2>
      <app-payment-list />
    </section>`,
})
export class Transfers {
  bank = inject(Bank);
  route = inject(ActivatedRoute);
  source =
    this.route.snapshot.queryParamMap.get("wallet") ??
    this.bank.wallets().find(ready)?.id ??
    "";
  phone = "";
  amount = "";
  confirmation: Command | null = null;
  clear() {
    this.bank.recipient.set(null);
    this.confirmation = null;
  }
  review() {
    void this.bank.task(async () => {
      const wallet = this.bank.wallets().find((w) => w.id === this.source);
      const r = this.bank.recipient();
      if (
        !wallet ||
        !ready(wallet) ||
        wallet.owner_id !== this.bank.owner() ||
        !r ||
        r.phone_number !== this.phone
      )
        throw Error("Confirm an eligible account and recipient first.");
      this.confirmation = {
        source_wallet_id: wallet.id,
        recipient_id: r.identity_id,
        recipient_phone: r.phone_number,
        currency: "AED",
        amount_minor: minor(this.amount),
      };
    });
  }
  confirm() {
    const command = this.confirmation;
    this.confirmation = null;
    if (command) void this.bank.send(command);
  }
}
