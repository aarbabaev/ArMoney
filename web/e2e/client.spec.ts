import { test, expect, Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
const owner = "11111111-1111-4111-8111-111111111111",
  recipient = "22222222-2222-4222-8222-222222222222",
  wallet = "33333333-3333-4333-8333-333333333333",
  paymentId = "44444444-4444-4444-8444-444444444444";

test("initial callback navigation mounts the callback and rejects missing OIDC state", async ({ page }) => {
  const bankRequests: string[] = [];
  page.on("request", request => {
    if (new URL(request.url()).pathname.startsWith("/v1/")) bankRequests.push(request.method());
  });
  await page.goto("/oauth/callback?error=invalid_request&state=synthetic-missing-state");
  await expect(page.getByRole("heading", { name: "Completing secure sign-in" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveText("No matching state found in storage");
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toHaveCount(0);
  expect(bankRequests).toEqual([]);
});
const command = {
  source_wallet_id: wallet,
  recipient_id: recipient,
  recipient_phone: "+971501234567",
  currency: "AED",
  amount_minor: 101,
};
const result = {
  ...command,
  id: paymentId,
  requester_id: owner,
  destination_wallet_id: wallet,
  status: "PENDING",
  rejection_reason: null,
  created_at: "2026-10-13T00:00:00Z",
  updated_at: "2026-10-13T00:00:00Z",
};
async function mock(page: Page, identity = () => owner) {
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname;
    let body: any = {};
    if (path === "/v1/auth/me") body = { id: identity(), email: null };
    else if (path === "/v1/wallets")
      body = {
        wallets: [
          {
            id: wallet,
            owner_id: identity(),
            currency: "AED",
            status: "ACTIVE",
            provisioning_status: "READY",
            ledger_account_id: wallet,
          },
        ],
      };
    else if (path.endsWith("/balance"))
      return route.fulfill({
        contentType: "application/json",
        body: `{"wallet_id":"${wallet}","currency":"AED","balance_minor":9223372036854775807}`,
      });
    else if (path === "/v1/users/me")
      body = {
        id: wallet,
        identity_id: identity(),
        display_name: "Test owner",
        phone_number: "+971501234567",
        phone_verified: false,
      };
    else if (path === "/v1/payments/page")
      body = { payments: [], next_cursor: null };
    else if (path === "/v1/notifications/page")
      body = { notifications: [], next_cursor: null };
    else if (path === "/v1/recipients/resolve")
      body = {
        identity_id: recipient,
        display_name: "Test recipient",
        phone_number: "+971501234567",
      };
    else if (path === "/v1/payments") body = result;
    else if (path === "/v1/auth/logout") return route.fulfill({ status: 204 });
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
}
async function signIn(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(
    page.getByRole("heading", { name: "Hello, Test owner" }),
  ).toBeVisible();
  await expect(
    page.getByText("AED 92233720368547758.07", { exact: true }),
  ).toBeVisible();
}

test("profile form submits the edited name to the gateway", async ({ page }) => {
  await mock(page);
  let displayName: string | null = null;
  let releaseProfile!: () => void;
  const heldProfile = new Promise<void>(resolve => { releaseProfile = resolve; });
  await page.route("**/v1/users/me", async route => {
    if (route.request().method() === "PUT") displayName = route.request().postDataJSON().display_name;
    if (displayName === null) {
      await heldProfile;
      return route.fulfill({ status: 404, contentType: "application/json", body: '{"error":"not_found"}' });
    }
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({
      id: wallet, identity_id: owner, display_name: displayName,
      phone_number: "+971501234567", phone_verified: false,
    }) });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toBeVisible();
  await page.getByRole("navigation").getByRole("link", { name: "Profile", exact: true }).click();
  await expect(page.getByRole("button", { name: "Save name", exact: true })).toBeDisabled();
  releaseProfile();
  await expect(page.getByRole("button", { name: "Save name", exact: true })).toBeEnabled();
  await page.getByLabel("Display name", { exact: true }).fill("Edited owner");
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === "/v1/users/me" && response.request().method() === "PUT");
  await page.getByRole("button", { name: "Save name", exact: true }).click();
  const response = await saved;
  expect(response.status()).toBe(200);
  expect(response.request().postDataJSON()).toEqual({ display_name: "Edited owner" });
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toBeEnabled();
  expect(displayName).toBe("Edited owner");
});
test("mobile/desktop light/dark UI is keyboard labelled and shows exact ledger balance", async ({
  page,
}) => {
  await mock(page);
  await signIn(page);
  for (const viewport of [
    { width: 390, height: 844 },
    { width: 1440, height: 900 },
  ])
    for (const scheme of ["light", "dark"] as const) {
      await page.setViewportSize(viewport);
      await page.emulateMedia({ colorScheme: scheme });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      const violations = (await new AxeBuilder({ page }).analyze()).violations;
      expect(violations).toEqual([]);
    }
  await page.getByRole("link", { name: "Accounts", exact: true }).click();
  await page.getByRole("link", { name: /AED account/ }).click();
  await expect(page.getByText(/not an IBAN/)).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Send from this account" }),
  ).toBeVisible();
});
test("uncertain command survives reload/logout and retry uses the exact original key/body", async ({
  page,
}) => {
  await mock(page);
  const posted: { key: string | undefined; body: string | null }[] = [];
  await page.route("**/v1/payments", async (route) => {
    posted.push({
      key: route.request().headers()["idempotency-key"],
      body: route.request().postData(),
    });
    if (posted.length === 1) await route.abort("failed");
    else
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ ...result, status: "COMPLETED" }),
      });
  });
  await signIn(page);
  await page.getByRole("link", { name: "Transfers", exact: true }).click();
  await page.getByLabel("Source account").selectOption(wallet);
  await page.getByLabel("Recipient UAE mobile number").fill("+971501234567");
  await page.getByRole("button", { name: "Find recipient" }).click();
  await expect(page.getByText("Test recipient", { exact: true })).toBeVisible();
  await page.getByLabel("Amount (AED)").fill("1.01");
  await page.getByRole("button", { name: "Review transfer" }).click();
  await page
    .getByRole("button", { name: "Confirm transfer", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Saved transfer requires confirmation" }),
  ).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await page.getByRole("link", { name: "Transfers", exact: true }).click();
  await page
    .getByRole("button", { name: "Retry saved transfer safely" })
    .click();
  await expect(
    page.getByText("Transfer completed.", { exact: true }),
  ).toBeVisible();
  expect(posted).toHaveLength(2);
  expect(posted[1]).toEqual(posted[0]);
  await expect(
    page.getByRole("heading", { name: "Saved transfer requires confirmation" }),
  ).toHaveCount(0);
});
test("owner switching hides prior local cards; permanent closure requires confirmation", async ({
  page,
}) => {
  let identity = owner;
  await mock(page, () => identity);
  await signIn(page);
  await page.getByRole("link", { name: "Demo cards", exact: true }).click();
  await page.getByLabel("Account", { exact: true }).selectOption(wallet);
  await page.getByRole("button", { name: "Create demonstration card" }).click();
  await expect(page.getByRole("heading", { name: /DEMO-/ })).toHaveCount(1);
  await page.getByRole("button", { name: "Freeze", exact: true }).click();
  await expect(
    page.getByText("VIRTUAL · FROZEN", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Close card", exact: true }).click();
  await page.getByRole("button", { name: "Confirm closure" }).click();
  await expect(
    page.getByText("VIRTUAL · CLOSED", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Unfreeze" })).toHaveCount(0);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  identity = recipient;
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await page.getByRole("link", { name: "Demo cards", exact: true }).click();
  await expect(page.getByRole("heading", { name: /DEMO-/ })).toHaveCount(0);
});
test("assistant cannot fabricate answers and balance outages remain unavailable", async ({
  page,
}) => {
  await mock(page);
  await page.route("**/v1/wallets/*/balance", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: '{"error":"dependency_unavailable"}',
    }),
  );
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(
    page.getByText("Balance unavailable", { exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Assistant", exact: true }).click();
  await page.route("**/v1/assistant/messages", route => route.fulfill({status:503,contentType:"application/json",body:'{"error":"service_unavailable"}'}));
  await page.getByLabel("Your question").fill("What is my balance?");
  await page.getByRole("button", { name: "Send question" }).click();
  await expect(page.getByRole("alert")).toContainText("Assistant unavailable");
  await expect(page.getByRole("region", { name: "Assistant answer" })).toHaveCount(0);
});
test("two tabs serialize owner commands and persist no session credentials", async ({
  page,
  context,
}) => {
  const second = await context.newPage();
  const posts: string[] = [];
  for (const tab of [page, second]) {
    await mock(tab);
    await tab.route("**/v1/payments", async (route) => {
      posts.push(route.request().headers()["idempotency-key"]);
      await route.abort("failed");
    });
    await signIn(tab);
    await tab.getByRole("link", { name: "Transfers", exact: true }).click();
    await tab.getByLabel("Source account").selectOption(wallet);
    await tab.getByLabel("Recipient UAE mobile number").fill("+971501234567");
    await tab.getByRole("button", { name: "Find recipient" }).click();
    await expect(
      tab.getByText("Test recipient", { exact: true }),
    ).toBeVisible();
    await tab.getByLabel("Amount (AED)").fill("1.01");
    await tab.getByRole("button", { name: "Review transfer" }).click();
  }
  await Promise.all([
    page.getByRole("button", { name: "Confirm transfer", exact: true }).click(),
    second
      .getByRole("button", { name: "Confirm transfer", exact: true })
      .click(),
  ]);
  await expect.poll(() => posts.length).toBe(1);
  const stored = await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const r = indexedDB.open("armoney-browser-v1", 1);
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
    return await new Promise<string[]>((resolve, reject) => {
      const r = db.transaction("records").objectStore("records").getAll();
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  });
  expect(stored.join("")).not.toContain("synthetic-ui-session");
  expect(stored.join("")).not.toContain("access_token");
  expect(stored.join("")).toContain(posts[0]);
});
test("corrupted recovery storage disables payments and preserves the record", async ({
  page,
}) => {
  await mock(page);
  await page.goto("/");
  await page.evaluate(
    async ({ owner }) => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const r = indexedDB.open("armoney-browser-v1", 1);
        r.onupgradeneeded = () => r.result.createObjectStore("records");
        r.onsuccess = () => resolve(r.result);
        r.onerror = () => reject(r.error);
      });
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction("records", "readwrite");
        tx.objectStore("records").put(
          '{"corrupted":true}',
          `${location.origin}|${owner}|pending`,
        );
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      });
    },
    { owner },
  );
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Recovery storage is blocked",
  );
  await page.getByRole("link", { name: "Transfers", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Review transfer" }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Find recipient" }),
  ).toBeDisabled();
});

test("assistant renders escaped citations and exact facts, then exposes unavailable without stale answer", async ({ page }) => {
  await mock(page); await signIn(page);
  let unavailable = false;
  await page.route("**/v1/assistant/messages", async route => {
    expect(route.request().postDataJSON()).toEqual({message:"What is my balance?"});
    expect(route.request().headers()["authorization"]).toMatch(/^Bearer /);
    if (unavailable) return route.fulfill({status:503,contentType:"application/json",body:'{"error":"service_unavailable"}'});
    await route.fulfill({contentType:"application/json",body:JSON.stringify({reply:"<img src=x onerror=alert(1)>",outcome:"answered",citations:[{document_id:"demo",version:"1",section:"AED rules",excerpt:"<script>alert(1)</script>"}],facts:{wallets:[{wallet_id:wallet,currency:"AED",balance_minor:"BIG_AMOUNT"}],last_transaction:null},provenance:[{source:"wallet-service",fetched_at:"2026-10-14T00:00:00Z",scope:"owned active wallets"},{source:"policy-index",fetched_at:"2026-10-14T00:00:00Z",scope:"demonstration rules"}]}).replace('"BIG_AMOUNT"','9223372036854775807')});
  });
  await page.getByRole("link",{name:"Assistant"}).click();
  await page.getByLabel("Your question").fill("What is my balance?");
  await page.getByRole("button",{name:"Send question"}).click();
  const answer = page.getByRole("region",{name:"Assistant answer"});
  await expect(answer.getByText("<img src=x onerror=alert(1)>",{exact:true})).toBeVisible();
  await expect(answer.getByText(/AED 92233720368547758.07/)).toBeVisible();
  await answer.locator("summary").click();
  await expect(answer.getByText("<script>alert(1)</script>",{exact:true})).toBeVisible();
  expect(await answer.locator("img,script").count()).toBe(0);
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  unavailable = true;
  await page.getByRole("button",{name:"Send question"}).click();
  await expect(page.getByRole("alert")).toContainText("Assistant unavailable");
  await expect(answer).toHaveCount(0);
});
