import { expect, test } from "@playwright/test";

test("the first editable profile input retains its edit and submits", async ({ page }) => {
  const owner = "11111111-1111-4111-8111-111111111111";
  let displayName: string | null = null;
  await page.route("**/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown;
    if (path === "/v1/auth/me") body = { id: owner };
    else if (path === "/v1/wallets") body = { wallets: [] };
    else if (path === "/v1/payments/page") body = { payments: [], next_cursor: null };
    else if (path === "/v1/notifications/page") body = { notifications: [], next_cursor: null };
    else if (path === "/v1/users/me") {
      if (route.request().method() === "PUT") displayName = route.request().postDataJSON().display_name;
      if (displayName === null) return route.fulfill({ status: 404, contentType: "application/json", body: '{"error":"not_found"}' });
      body = { id: owner, identity_id: owner, display_name: displayName, phone_number: "+971501234567", phone_verified: false };
    } else throw Error("Unexpected fixture request");
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toBeEnabled();

  // Observe actual native editability, including a disabled parent fieldset.
  // A pre-listener event on a disabled/unready control is not user input.
  await page.evaluate(() => {
    const probe = { node: null as HTMLInputElement | null, initiallyDisabled: false, edited: false };
    (window as any).profileReadinessProbe = probe;
    const observer = new MutationObserver(records => {
      const input = document.querySelector<HTMLInputElement>("input#name");
      if (!input) return;
      if (!probe.node) {
        probe.node = input;
      }
      // Attribute history also proves the disabled state when Angular completes
      // both renders before the observer callback is delivered.
      probe.initiallyDisabled ||= input.matches(":disabled") || records.some(record =>
        record.attributeName === "disabled" && record.oldValue !== null &&
        record.target instanceof HTMLFieldSetElement && record.target.contains(input));
      if (input.matches(":disabled")) return;
      observer.disconnect();
      input.value = "Early edit";
      input.dispatchEvent(new Event("input", { bubbles: true }));
      probe.edited = true;
    });
    observer.observe(document.querySelector("app-root")!, { childList: true, subtree: true, attributes: true, attributeFilter: ["disabled"], attributeOldValue: true });
  });
  const profile = page.getByRole("navigation").getByRole("link", { name: "Profile", exact: true });
  await profile.click();
  await expect(profile).toHaveAttribute("aria-current", "page");
  await expect(page.getByLabel("Display name", { exact: true })).toBeEnabled();
  expect(await page.evaluate(() => {
    const probe = (window as any).profileReadinessProbe;
    return { initiallyDisabled: probe.initiallyDisabled, sameNode: probe.node === document.querySelector("input#name"), edited: probe.edited };
  })).toEqual({ initiallyDisabled: true, sameNode: true, edited: true });
  await expect(page.getByLabel("Display name", { exact: true })).toHaveValue("Early edit");
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === "/v1/users/me" && response.request().method() === "PUT");
  await page.getByRole("button", { name: "Save name", exact: true }).click();
  const response = await saved;
  expect(response.status()).toBe(200);
  expect(response.request().postDataJSON()).toEqual({ display_name: "Early edit" });
});
