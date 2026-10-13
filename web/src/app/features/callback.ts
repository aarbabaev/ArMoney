import { Component, inject } from "@angular/core";
import { Router } from "@angular/router";
import { Bank } from "../core/bank";
@Component({
  standalone: true,
  template: `<h1>Completing secure sign-in</h1>
    <p>Please wait while the bank verifies your identity.</p>`,
})
export class Callback {
  bank = inject(Bank);
  router = inject(Router);
  constructor() {
    if (this.bank.owner()) void this.router.navigateByUrl("/home");
    else
      void this.bank.callback().then(() => {
        if (this.bank.owner()) void this.router.navigateByUrl("/home");
      });
  }
}
