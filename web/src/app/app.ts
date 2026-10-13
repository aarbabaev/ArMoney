import { Component, inject } from "@angular/core";
import {
  provideRouter,
  RouterOutlet,
  RouterLink,
  RouterLinkActive,
  Router,
  Routes,
} from "@angular/router";
import { Bank } from "./core/bank";
import { Home } from "./features/home";
import { Accounts } from "./features/accounts";
import { Account } from "./features/account";
import { Transfers } from "./features/transfers";
import { Inbox } from "./features/inbox";
import { Profile } from "./features/profile";
import { Cards } from "./features/cards";
import { Assistant } from "./features/assistant";
import { Callback } from "./features/callback";
export const routes: Routes = [
  { path: "home", component: Home },
  { path: "accounts", component: Accounts },
  { path: "accounts/:id", component: Account },
  { path: "transfers", component: Transfers },
  { path: "inbox", component: Inbox },
  { path: "profile", component: Profile },
  { path: "cards", component: Cards },
  { path: "assistant", component: Assistant },
  { path: "oauth/callback", component: Callback },
  { path: "oauth/logout", redirectTo: "home" },
  { path: "", redirectTo: "home", pathMatch: "full" },
  { path: "**", redirectTo: "home" },
];
@Component({
  selector: "app-root",
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `<a class="skip" href="#main">Skip to content</a>
    <header>
      <a routerLink="/home" class="brand"
        ><span class="brand-mark">A</span> ArMoney</a
      ><span class="tagline">Money, thoughtfully connected.</span>
      @if (bank.owner()) {
        <button
          class="secondary"
          (click)="bank.task(refresh)"
          [disabled]="bank.busy()"
        >
          {{ bank.busy() ? "Updating…" : "Refresh" }}
        </button>
      }
    </header>
    @if (bank.owner()) {
      <div class="shell">
        <nav aria-label="Main navigation">
          @for (link of links; track link.path) {
            <a
              [routerLink]="link.path"
              routerLinkActive="selected"
              ariaCurrentWhenActive="page"
              ><span aria-hidden="true">{{ link.icon }}</span
              >{{ link.label }}</a
            >
          }
          <button class="secondary signout" (click)="bank.logout()">
            Sign out
          </button>
        </nav>
        <main id="main" tabindex="-1">
          @if (bank.message()) {
            <p role="status" class="message">{{ bank.message() }}</p>
          }
          @if (bank.blocked()) {
            <p role="alert" class="message">
              Recovery storage is blocked. Payments are disabled and saved data
              has been preserved.
            </p>
          }
          <router-outlet />
          @if (bank.detail(); as p) {
            <section
              class="panel detail"
              role="dialog"
              aria-modal="false"
              aria-labelledby="detail-title"
            >
              <h2 id="detail-title">Transfer details</h2>
              <p class="balance">{{ bank.money(p.amount_minor) }}</p>
              <p>
                {{ p.status }}
                @if (p.status === "PENDING") {
                  · Awaiting ledger confirmation
                }
              </p>
              <dl>
                <dt>Transfer identifier</dt>
                <dd class="identifier">{{ p.id }}</dd>
                <dt>Recipient</dt>
                <dd>{{ p.recipient_phone }}</dd>
                <dt>Source wallet</dt>
                <dd class="identifier">{{ p.source_wallet_id }}</dd>
                <dt>Created</dt>
                <dd>{{ p.created_at }}</dd>
                @if (p.rejection_reason) {
                  <dt>Reason</dt>
                  <dd>{{ p.rejection_reason }}</dd>
                }
              </dl>
              <button class="secondary" (click)="bank.detail.set(null)">
                Close details
              </button>
            </section>
          }
        </main>
      </div>
    } @else {
      <main id="main" tabindex="-1" class="welcome">
        @if (isCallback()) {
          <router-outlet />
        } @else {
          <p class="eyebrow">WELCOME TO ARMONEY</p>
          <h1>Your money.<br />Your connections.</h1>
          <p class="intro">
            Manage your AED accounts and send money to registered UAE mobile
            numbers.
          </p>
          <button (click)="bank.signIn()" [disabled]="bank.busy()">
            Sign in securely →
          </button>
          <p>
            Use the bank’s secure browser sign-in. A refresh requires signing in
            again; uncertain transfers remain saved for their verified owner.
          </p>
        }
        @if (bank.message()) {
          <p role="alert" class="message">{{ bank.message() }}</p>
        }
      </main>
    }
    <footer>ArMoney · AED accounts and registered-phone transfers</footer>`,
})
export class App {
  bank = inject(Bank);
  router = inject(Router);
  links = [
    { path: "/home", label: "Home", icon: "⌂" },
    { path: "/accounts", label: "Accounts", icon: "◉" },
    { path: "/transfers", label: "Transfers", icon: "↗" },
    { path: "/inbox", label: "Inbox", icon: "✉" },
    { path: "/cards", label: "Demo cards", icon: "▤" },
    { path: "/assistant", label: "Assistant", icon: "✦" },
    { path: "/profile", label: "Profile", icon: "●" },
  ];
  refresh = () => this.bank.refresh();
  isCallback() {
    return this.router.url.startsWith("/oauth/callback");
  }
}
