import { expect, Page, test } from "@playwright/test";

const owner = "11111111-1111-4111-8111-111111111111";
const recipient = "22222222-2222-4222-8222-222222222222";
const wallet = "33333333-3333-4333-8333-333333333333";

async function signIn(page: Page) {
  await page.route("**/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown;
    if (path === "/v1/auth/me") body = { id: owner };
    else if (path === "/v1/wallets") body = { wallets: [{ id: wallet, owner_id: owner, currency: "AED", status: "ACTIVE", provisioning_status: "READY", ledger_account_id: wallet }] };
    else if (path.endsWith("/balance")) body = { wallet_id: wallet, currency: "AED", balance_minor: 0 };
    else if (path === "/v1/payments/page") body = { payments: [], next_cursor: null };
    else if (path === "/v1/notifications/page") body = { notifications: [], next_cursor: null };
    else if (path === "/v1/users/me") return route.fulfill({ status: 404, contentType: "application/json", body: '{"error":"not_found"}' });
    else if (path === "/v1/recipients/resolve") body = { identity_id: recipient, display_name: "Recipient", phone_number: "+971501234567" };
    else if (path === "/v1/assistant/messages") return route.fulfill({ status: 503, contentType: "application/json", body: '{"error":"service_unavailable"}' });
    else throw Error("Unexpected fixture request");
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toBeEnabled();
}

async function earliestEdit(page: Page, linkName: string, selector: string, value: string, event: string) {
  await page.evaluate(({ selector, value, event }) => {
    const probe = { node: null as Element | null, initiallyDisabled: false, edited: false };
    (window as any).formReadinessProbe = probe;
    const observer = new MutationObserver(records => {
      const input = document.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(selector);
      if (!input) return;
      if (!probe.node) probe.node = input;
      probe.initiallyDisabled ||= input.matches(":disabled") || records.some(record =>
        record.attributeName === "disabled" && record.oldValue !== null &&
        record.target instanceof HTMLFieldSetElement && record.target.contains(input));
      if (input.matches(":disabled")) return;
      observer.disconnect();
      input.value = value;
      input.dispatchEvent(new Event(event, { bubbles: true }));
      probe.edited = true;
    });
    observer.observe(document.querySelector("app-root")!, { childList: true, subtree: true, attributes: true, attributeFilter: ["disabled"], attributeOldValue: true });
  }, { selector, value, event });
  const link = page.getByRole("navigation").getByRole("link", { name: linkName, exact: true });
  await link.click();
  await expect(link).toHaveAttribute("aria-current", "page");
  await expect(page.locator(selector)).toBeEnabled();
  expect(await page.evaluate(selector => {
    const probe = (window as any).formReadinessProbe;
    return { initiallyDisabled: probe.initiallyDisabled, sameNode: probe.node === document.querySelector(selector), edited: probe.edited };
  }, selector)).toEqual({ initiallyDisabled: true, sameNode: true, edited: true });
  await expect(page.locator(selector)).toHaveValue(value);
}

test("the assistant accepts its first editable question without losing the model value", async ({ page }) => {
  await signIn(page);
  await earliestEdit(page, "Assistant", "#assistant-question", "What is my balance?", "input");
  const sent = page.waitForRequest(request => new URL(request.url()).pathname === "/v1/assistant/messages");
  await page.getByRole("button", { name: "Send question", exact: true }).click();
  expect((await sent).postDataJSON()).toEqual({ message: "What is my balance?" });
  await expect(page.getByRole("alert")).toContainText("Assistant unavailable");
});

test("the transfer form retains the first editable recipient without sending a payment", async ({ page }) => {
  await signIn(page);
  const paymentPosts: string[] = [];
  page.on("request", request => {
    if (new URL(request.url()).pathname === "/v1/payments" && request.method() === "POST") paymentPosts.push(request.method());
  });
  await earliestEdit(page, "Transfers", "#phone", "+971501234567", "input");
  const sent = page.waitForRequest(request => new URL(request.url()).pathname === "/v1/recipients/resolve");
  await page.getByRole("button", { name: "Find recipient", exact: true }).click();
  expect((await sent).postDataJSON()).toEqual({ phone_number: "+971501234567" });
  await expect(page.locator(".recipient")).toContainText("Recipient");
  expect(paymentPosts).toEqual([]);
});

test("the demo card form retains the first editable type", async ({ page }) => {
  await signIn(page);
  await earliestEdit(page, "Demo cards", "#type", "PHYSICAL", "change");
  await page.getByRole("button", { name: "Create demonstration card", exact: true }).click();
  await expect(page.getByText("PHYSICAL · ACTIVE", { exact: true })).toBeVisible();
  await expect(page.getByText("VIRTUAL · ACTIVE", { exact: true })).toHaveCount(0);
});
