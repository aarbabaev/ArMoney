// Isolated transport/UI harness. Never compiled by the production target.
import { Injectable } from "@angular/core";
import { bootstrapApplication } from "@angular/platform-browser";
import { provideRouter } from "@angular/router";
import { App, routes } from "../src/app/app";
import { Bank } from "../src/app/core/bank";
@Injectable()
class SyntheticBank extends Bank {
  override signIn() {
    return this.task(async () => {
      const identity = await this.request("/v1/auth/me");
      (this as any).generation++;
      this.owner.set(identity.id);
      Object.assign(this, {
        token: "synthetic-ui-session-".padEnd(43, "x"),
        expires: Date.now() + 1800000,
      });
      await this.refresh();
    });
  }
  override async logout() {
    const token = (this as any).token;
    (this as any).clear();
    try {
      await this.request(
        "/v1/auth/logout",
        "POST",
        undefined,
        undefined,
        token,
      );
    } catch {
      this.message.set("Synthetic logout failed.");
    }
  }
}
bootstrapApplication(App, {
  providers: [
    provideRouter(routes),
    { provide: Bank, useClass: SyntheticBank },
  ],
}).catch(() => {
  document.body.textContent = "UI test harness failed.";
});
