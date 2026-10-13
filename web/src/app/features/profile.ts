import { Component, inject } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Bank } from "../core/bank";
@Component({
  standalone: true,
  imports: [FormsModule],
  template: `<h1>Profile</h1>
    <section class="panel">
      <form (ngSubmit)="bank.saveName(name)">
        <label for="name">Display name</label
        ><input
          id="name"
          name="name"
          maxlength="100"
          autocomplete="name"
          [(ngModel)]="name"
        /><button [disabled]="bank.busy()">Save name</button>
      </form>
      <h2>Registered phone</h2>
      <p>
        {{ bank.profile()?.phone_number ?? "No registered phone available" }}
      </p>
      <p>
        {{
          bank.profile()?.phone_verified
            ? "Phone ownership verified"
            : "Phone ownership has not been verified"
        }}
      </p>
      <p>
        The registered phone is immutable. This client cannot verify phone
        ownership; registered claims can receive transfers.
      </p>
      <h2>Bank identity</h2>
      <p class="identifier">{{ bank.owner() }}</p>
      <button class="secondary" (click)="bank.logout()">Sign out</button>
    </section>`,
})
export class Profile {
  bank = inject(Bank);
  name = this.bank.profile()?.display_name ?? "";
}
