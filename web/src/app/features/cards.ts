import { Component, inject } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Bank } from "../core/bank";
import { FormReadiness } from "../core/form-readiness";
import { ready } from "../core/contracts";
@Component({
  standalone: true,
  imports: [FormsModule, FormReadiness],
  template: `<h1>Demonstration cards</h1>
    <p>
      Local demonstrations only. These are not payment cards, have no PAN or
      CVV, and cannot spend money.
    </p>
    @if (bank.cardError()) {
      <p role="alert">{{ bank.cardError() }}</p>
    }
    <form class="panel" (ngSubmit)="issue()">
      <fieldset disabled armoneyFormReady class="form-fields">
      <label for="card-wallet">Account</label
      ><select id="card-wallet" name="wallet" [(ngModel)]="walletId">
        @for (w of bank.wallets(); track w.id) {
          @if (bank.ready(w)) {
            <option [value]="w.id">AED · {{ w.id }}</option>
          }
        }</select
      ><label for="type">Demonstration type</label
      ><select id="type" name="type" [(ngModel)]="type">
        <option value="VIRTUAL">Virtual</option>
        <option value="PHYSICAL">Physical</option></select
      ><button [disabled]="bank.busy() || !!bank.cardError()">
        Create demonstration card
      </button>
      </fieldset>
    </form>
    <div class="grid">
      @for (c of bank.cards(); track c.id) {
        <article class="panel tonal">
          <p class="eyebrow">ARMONEY · DEMONSTRATION</p>
          <h2>DEMO-{{ c.id.slice(-4).toUpperCase() }}</h2>
          <p>{{ c.type }} · {{ c.status }}</p>
          <p class="identifier">Account {{ c.walletId }}</p>
          @if (c.status !== "CLOSED") {
            <button
              class="secondary"
              [disabled]="bank.busy() || !!bank.cardError()"
              (click)="
                bank.card(
                  null,
                  c.type,
                  c.id,
                  c.status === 'ACTIVE' ? 'FROZEN' : 'ACTIVE'
                )
              "
            >
              {{ c.status === "ACTIVE" ? "Freeze" : "Unfreeze" }}</button
            ><button
              class="secondary"
              [disabled]="bank.busy() || !!bank.cardError()"
              (click)="closing = c.id"
            >
              Close card
            </button>
            @if (closing === c.id) {
              <p>Closure is permanent for this demonstration card.</p>
              <button
                (click)="
                  bank.card(null, c.type, c.id, 'CLOSED'); closing = null
                "
              >
                Confirm closure</button
              ><button class="secondary" (click)="closing = null">
                Cancel
              </button>
            }
          }
        </article>
      }
    </div>`,
})
export class Cards {
  bank = inject(Bank);
  walletId = this.bank.wallets().find(ready)?.id ?? "";
  type: "VIRTUAL" | "PHYSICAL" = "VIRTUAL";
  closing: string | null = null;
  issue() {
    void this.bank.card(
      this.bank.wallets().find((w) => w.id === this.walletId) ?? null,
      this.type,
    );
  }
}
