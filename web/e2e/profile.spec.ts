import { expect, test } from "@playwright/test";

test("an edit during initial profile control registration survives and submits", async ({ page }) => {
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

  // Enter at DOM insertion, before deferred template-driven registration can
  // overwrite an edit. This deterministically exposes the original race.
  await page.evaluate(() => {
    const observer = new MutationObserver(() => {
      const input = document.querySelector<HTMLInputElement>("input#name");
      if (!input) return;
      observer.disconnect();
      input.value = "Early edit";
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    observer.observe(document.querySelector("app-root")!, { childList: true, subtree: true });
  });
  const profile = page.getByRole("navigation").getByRole("link", { name: "Profile", exact: true });
  await profile.click();
  await expect(profile).toHaveAttribute("aria-current", "page");
  await expect(page.getByLabel("Display name", { exact: true })).toHaveValue("Early edit");
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === "/v1/users/me" && response.request().method() === "PUT");
  await page.getByRole("button", { name: "Save name", exact: true }).click();
  const response = await saved;
  expect(response.status()).toBe(200);
  expect(response.request().postDataJSON()).toEqual({ display_name: "Early edit" });
});
