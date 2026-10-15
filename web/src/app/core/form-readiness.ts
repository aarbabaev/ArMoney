import { afterNextRender, ApplicationRef, DestroyRef, Directive, inject, signal } from "@angular/core";

/** Each new form remains natively disabled until its controls finish rendering. */
@Directive({
  selector: "fieldset[armoneyFormReady]",
  standalone: true,
  host: { "[disabled]": "!ready()" },
})
export class FormReadiness {
  readonly ready = signal(false);
  private readonly application = inject(ApplicationRef);
  private readonly lifecycle = inject(DestroyRef);
  constructor() {
    afterNextRender(() => {
      void this.application.whenStable().then(() => {
        if (!this.lifecycle.destroyed) this.ready.set(true);
      });
    });
  }
}
