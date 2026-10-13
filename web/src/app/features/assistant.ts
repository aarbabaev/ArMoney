import { Component } from "@angular/core";
export interface AssistantAdapter {
  available: boolean;
  send(message: string): Promise<string>;
}
export const assistantAdapter: AssistantAdapter = {
  available: false,
  async send() {
    throw Error("The assistant service is unavailable.");
  },
};
@Component({
  standalone: true,
  template: `<h1>Assistant</h1>
    <section class="panel tonal">
      <h2>The assistant is not available yet</h2>
      <p>You can manage accounts and transfers using the bank screens.</p>
      <label for="assistant">Message</label
      ><input
        id="assistant"
        placeholder="Assistant unavailable"
        disabled
      /><button disabled>Send message</button>
    </section>`,
})
export class Assistant {}
