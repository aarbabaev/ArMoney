import { Component, inject } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { Bank } from "../core/bank";
@Component({
  selector: "app-payment-list",
  standalone: true,
  template: `@for (p of filtered(); track p.id) {
      <button class="activity" (click)="bank.details(p.id)">
        <span
          >{{ p.requester_id === bank.owner() ? "↗ Outgoing" : "↙ Incoming" }}
          <strong>{{ bank.money(p.amount_minor) }}</strong></span
        ><span
          >{{ p.status }} · {{ p.created_at }}
          @if (p.status === "PENDING") {
            · Awaiting ledger confirmation
          }
        </span>
      </button>
    } @empty {
      <p class="empty">No activity loaded.</p>
    }
    @if (bank.paymentCursor()) {
      <button
        class="secondary"
        [disabled]="bank.busy()"
        (click)="bank.task(more)"
      >
        Load more transfers
      </button>
    }`,
})
export class PaymentList {
  bank = inject(Bank);
  route = inject(ActivatedRoute);
  filtered() {
    const id = this.route.snapshot.paramMap.get("id");
    return this.bank
      .payments()
      .filter(
        (p) =>
          !id || p.source_wallet_id === id || p.destination_wallet_id === id,
      );
  }
  more = () => this.bank.page("payments");
}
