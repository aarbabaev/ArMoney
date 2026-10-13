import { Component, inject } from "@angular/core";
import { Bank } from "../core/bank";
@Component({
  standalone: true,
  template: `<h1>Inbox</h1>
    <p>
      Payment notifications are stored by the bank. SMS and push delivery are
      not available.
    </p>
    <section class="panel">
      @for (n of bank.notices(); track n.id) {
        <article class="notice">
          <h2>{{ n.type.replaceAll("_", " ") }}</h2>
          <p>{{ bank.money(n.amount_minor) }} · {{ n.created_at }}</p>
          <button
            class="secondary"
            (click)="bank.details(n.payment_id)"
            [disabled]="bank.busy()"
          >
            View transfer
          </button>
          @if (!n.read_at) {
            <button (click)="bank.readNotice(n.id)" [disabled]="bank.busy()">
              Mark read
            </button>
          } @else {
            <span>Read</span>
          }
        </article>
      } @empty {
        <p class="empty">No notifications loaded.</p>
      }
      @if (bank.noticeCursor()) {
        <button (click)="bank.task(more)" [disabled]="bank.busy()">
          Load more notifications
        </button>
      }
    </section>`,
})
export class Inbox {
  bank = inject(Bank);
  more = () => this.bank.page("notifications");
}
