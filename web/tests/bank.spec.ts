import "@angular/compiler";
import "fake-indexeddb/auto";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Bank, HttpFailure } from "../src/app/core/bank";
const owner = "11111111-1111-4111-8111-111111111111",
  recipient = "22222222-2222-4222-8222-222222222222",
  id = "33333333-3333-4333-8333-333333333333";
const deferred = () => {
  let resolve!: (v: any) => void;
  const promise = new Promise((r) => (resolve = r));
  return { promise, resolve };
};
beforeEach(() => {
  vi.stubGlobal("location", {
    origin: "https://localhost:4200",
    protocol: "https:",
  });
  vi.stubGlobal("sessionStorage", {
    getItem: () => null,
    setItem: () => {},
    removeItem: () => {},
    length: 0,
    key: () => null,
  });
  vi.stubGlobal("navigator", {
    locks: { request: (_key: string, fn: () => Promise<any>) => fn() },
  });
});
function bank() {
  const b = new Bank();
  b.owner.set(owner);
  Object.assign(b, { token: "a".repeat(43), expires: Date.now() + 100000 });
  vi.spyOn((b as any).oidc, "removeUser").mockResolvedValue(undefined);
  vi.spyOn((b as any).oidc, "signoutRedirect").mockResolvedValue(undefined);
  return b;
}
describe("session isolation and recovery", () => {
  it("logs out immediately during actual held refresh and preserves a durable uncertain transfer", async () => {
    const b = bank(),
      held = deferred();
    const p = await b.store.lock(owner, () =>
      b.store.create(owner, {
        source_wallet_id: id,
        recipient_id: recipient,
        recipient_phone: "+971501234567",
        currency: "AED",
        amount_minor: 10n,
      }),
    );
    await b.store.write(owner, "pending", { ...p, attempted: true });
    vi.spyOn(b, "request").mockImplementation(async (path) =>
      path === "/v1/auth/logout" ? null : held.promise,
    );
    const refresh = b.task(() => b.refresh());
    await vi.waitFor(() => expect(b.busy()).toBe(true));
    const logout = b.logout();
    expect(b.owner()).toBeNull();
    expect(b.pending()).toBeNull();
    expect(b.wallets()).toEqual([]);
    await logout;
    held.resolve({ id: owner });
    await refresh;
    expect(b.owner()).toBeNull();
    expect(b.wallets()).toEqual([]);
    expect(await b.store.pending(owner)).toEqual({ ...p, attempted: true });
  });
  it("discards held cards after logout during refresh", async () => {
    const b = bank(),
      held = deferred();
    vi.spyOn(b.store, "pending").mockResolvedValue(null);
    vi.spyOn(b.store, "cards").mockReturnValue(held.promise as any);
    vi.spyOn(b, "request").mockImplementation(async (path) =>
      path === "/v1/auth/me"
        ? { id: owner }
        : path === "/v1/wallets"
          ? { wallets: [] }
          : path.includes("payments/page")
            ? { payments: [], next_cursor: null }
            : path.includes("notifications/page")
              ? { notifications: [], next_cursor: null }
              : path === "/v1/users/me"
                ? { identity_id: owner }
                : null,
    );
    const refresh = b.refresh();
    await vi.waitFor(() => expect(b.store.cards).toHaveBeenCalled());
    await b.logout();
    held.resolve([
      {
        id,
        walletId: id,
        type: "VIRTUAL",
        status: "ACTIVE",
        createdAt: new Date().toISOString(),
      },
    ]);
    await refresh;
    expect(b.cards()).toEqual([]);
    expect(b.owner()).toBeNull();
  });
  it("preserves a real attempted command when a held transfer returns after logout", async () => {
    const b = bank(),
      held = deferred();
    await b.store.remove(owner, "pending");
    vi.spyOn(b, "verified").mockResolvedValue(owner);
    const req = vi
      .spyOn(b, "request")
      .mockImplementation(async (path) =>
        path === "/v1/auth/logout" ? null : held.promise,
      );
    const command = {
      source_wallet_id: id,
      recipient_id: recipient,
      recipient_phone: "+971501234567",
      currency: "AED" as const,
      amount_minor: 10n,
    };
    const sending = b.send(command);
    await vi.waitFor(() =>
      expect(req).toHaveBeenCalledWith(
        "/v1/payments",
        "POST",
        command,
        expect.any(String),
      ),
    );
    const saved = await b.store.pending(owner);
    expect(saved?.attempted).toBe(true);
    await b.logout();
    held.resolve({
      ...command,
      id,
      requester_id: owner,
      destination_wallet_id: id,
      status: "COMPLETED",
      rejection_reason: null,
    });
    await sending;
    expect(b.owner()).toBeNull();
    expect(b.detail()).toBeNull();
    expect(await b.store.pending(owner)).toEqual(saved);
  });
  it("discards held card data after genuine request expiry", async () => {
    const b = bank(),
      held = deferred();
    vi.spyOn(b.store, "pending").mockResolvedValue(null);
    vi.spyOn(b.store, "cards").mockReturnValue(held.promise as any);
    const mocked = vi
      .spyOn(b, "request")
      .mockImplementation(async (path) =>
        path === "/v1/auth/me"
          ? { id: owner }
          : path === "/v1/wallets"
            ? { wallets: [] }
            : path.includes("payments/page")
              ? { payments: [], next_cursor: null }
              : path.includes("notifications/page")
                ? { notifications: [], next_cursor: null }
                : path === "/v1/users/me"
                  ? { identity_id: owner }
                  : null,
      );
    const refresh = b.refresh();
    await vi.waitFor(() => expect(b.store.cards).toHaveBeenCalled());
    mocked.mockRestore();
    Object.assign(b, { expires: Date.now() - 1 });
    await expect(b.request("/v1/wallets")).rejects.toThrow("Session expired");
    held.resolve([
      {
        id,
        walletId: id,
        type: "VIRTUAL",
        status: "ACTIVE",
        createdAt: new Date().toISOString(),
      },
    ]);
    await refresh;
    expect(b.cards()).toEqual([]);
    expect(b.owner()).toBeNull();
  });
  it("expires a session and rejects old authenticated requests", async () => {
    const b = bank();
    Object.assign(b, { expires: Date.now() - 1 });
    await expect(b.request("/v1/wallets")).rejects.toThrow("Session expired");
    expect(b.owner()).toBeNull();
  });
  it("writes attempt marker before POST and preserves exact key on uncertainty", async () => {
    const b = bank(),
      p = {
        version: 1 as const,
        origin: location.origin,
        owner,
        key: crypto.randomUUID(),
        command: {
          source_wallet_id: id,
          recipient_id: recipient,
          recipient_phone: "+971501234567",
          currency: "AED" as const,
          amount_minor: 10n,
        },
        hash: "hash",
        attempted: false,
      };
    vi.spyOn(b, "verified").mockResolvedValue(owner);
    vi.spyOn(b.store, "pending").mockResolvedValue(p);
    const write = vi.spyOn(b.store, "write").mockResolvedValue();
    const request = vi
      .spyOn(b, "request")
      .mockRejectedValue(Error("Lost response"));
    await b.send();
    expect(write).toHaveBeenCalledWith(owner, "pending", {
      ...p,
      attempted: true,
    });
    expect(write.mock.invocationCallOrder[0]).toBeLessThan(
      request.mock.invocationCallOrder[0],
    );
    expect(request).toHaveBeenCalledWith(
      "/v1/payments",
      "POST",
      p.command,
      p.key,
    );
    expect(b.pending()?.key).toBe(p.key);
  });
  it("never sends if attempt storage fails", async () => {
    const b = bank();
    vi.spyOn(b, "verified").mockResolvedValue(owner);
    vi.spyOn(b.store, "pending").mockResolvedValue({
      version: 1,
      owner,
      origin: location.origin,
      key: "x",
      hash: "h",
      attempted: false,
      command: {
        source_wallet_id: id,
        recipient_id: recipient,
        recipient_phone: "+971501234567",
        currency: "AED",
        amount_minor: 1n,
      },
    });
    vi.spyOn(b.store, "write").mockRejectedValue(Error("Write failed"));
    const request = vi.spyOn(b, "request");
    await b.send();
    expect(request).not.toHaveBeenCalled();
    expect(b.message()).toBe("Write failed");
  });
});
describe("new session and stale revocation", () => {
  it("keeps the newer expiry when an earlier unauthenticated SSO response arrives late", async () => {
    const b = bank(), held = deferred();
    (b as any).clear();
    Object.assign(b, { expires: 0 });
    const newerExpiry = Date.now() + 1800000;
    const oldExpiry = Date.now() + 60000;
    let exchanges = 0;
    const response = (body: unknown) => new Response(JSON.stringify(body), {
      status: 200, headers: { "Content-Type": "application/json" },
    });
    vi.stubGlobal("history", { replaceState: vi.fn() });
    vi.stubGlobal("fetch", vi.fn(async (path: string) => {
      if (path === "/v1/auth/sso") {
        if (++exchanges === 1) return held.promise;
        return response({ access_token: "b".repeat(43), token_type: "Bearer", expires_at: new Date(newerExpiry).toISOString() });
      }
      return response({ id: recipient });
    }));
    vi.spyOn((b as any).oidc, "signinRedirectCallback").mockResolvedValue({ access_token: "synthetic-provider-token" });
    vi.spyOn(b, "refresh").mockResolvedValue();
    try {
      const old = b.callback();
      await vi.waitFor(() => expect(exchanges).toBe(1));
      await b.logout();
      await b.callback();
      expect((b as any).expires).toBe(newerExpiry);
      held.resolve(response({ access_token: "a".repeat(43), token_type: "Bearer", expires_at: new Date(oldExpiry).toISOString() }));
      await old;
      expect(b.owner()).toBe(recipient);
      expect((b as any).token).toBe("b".repeat(43));
      expect((b as any).expires).toBe(newerExpiry);
    } finally {
      (b as any).clear();
    }
  });
  it("does not exchange a provider result from an earlier login generation", async () => {
    const b = bank(), held = deferred();
    (b as any).clear();
    Object.assign(b, { expires: 0 });
    vi.stubGlobal("history", { replaceState: vi.fn() });
    const fetch = vi.fn(async (path: string) => new Response(JSON.stringify(
      path === "/v1/auth/sso"
        ? { access_token: "b".repeat(43), token_type: "Bearer", expires_at: new Date(Date.now() + 1800000).toISOString() }
        : { id: recipient },
    ), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    vi.spyOn((b as any).oidc, "signinRedirectCallback")
      .mockReturnValueOnce(held.promise).mockResolvedValue({ access_token: "new-provider-token" });
    vi.spyOn(b, "refresh").mockResolvedValue();
    try {
      const old = b.callback();
      await b.logout();
      await b.callback();
      const expiry = (b as any).expires;
      held.resolve({ access_token: "old-provider-token" });
      await old;
      expect(fetch.mock.calls.filter(([path]) => path === "/v1/auth/sso")).toHaveLength(1);
      expect(b.owner()).toBe(recipient);
      expect((b as any).token).toBe("b".repeat(43));
      expect((b as any).expires).toBe(expiry);
    } finally {
      (b as any).clear();
    }
  });
  it("keeps a newly verified login when an earlier logout returns HTTP 401", async () => {
    const b = bank(),
      held = deferred();
    vi.stubGlobal("history", { replaceState: vi.fn() });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) => {
        if (path === "/v1/auth/logout") return held.promise;
        return new Response(
          JSON.stringify(
            path === "/v1/auth/sso"
              ? {
                  access_token: "b".repeat(43),
                  token_type: "Bearer",
                  expires_at: new Date(Date.now() + 1800000).toISOString(),
                }
              : { id: recipient, email: null },
          ),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      }),
    );
    vi.spyOn((b as any).oidc, "signinRedirectCallback").mockResolvedValue({
      access_token: "synthetic-provider-token",
    });
    vi.spyOn(b, "refresh").mockResolvedValue();
    const logout = b.logout();
    await b.callback();
    expect(b.owner()).toBe(recipient);
    expect(b.busy()).toBe(false);
    held.resolve(
      new Response('{"error":"unauthorized"}', {
        status: 401,
        headers: { "Content-Type": "application/json" },
      }),
    );
    await logout;
    expect(b.owner()).toBe(recipient);
    expect((b as any).token).toBe("b".repeat(43));
    expect((b as any).oidc.signoutRedirect).not.toHaveBeenCalled();
    (b as any).clear();
  });
  it("expiry clears the busy flag and allows another sign-in task", async () => {
    const b = bank();
    b.busy.set(true);
    Object.assign(b, { expires: Date.now() - 1 });
    await expect(b.request("/v1/wallets")).rejects.toThrow("Session expired");
    expect(b.busy()).toBe(false);
    const action = vi.fn(async () => {});
    await b.task(action);
    expect(action).toHaveBeenCalledOnce();
  });
});
describe("cursor and notification contract", () => {
  it("does not restart exhausted history and supersedes held older page", async () => {
    const b = bank(),
      held = deferred();
    const request = vi
      .spyOn(b, "request")
      .mockReturnValueOnce(held.promise)
      .mockResolvedValue({ payments: [], next_cursor: null });
    const old = b.page("payments", true);
    await b.page("payments", true);
    held.resolve({ payments: [], next_cursor: "old" });
    await old;
    expect(b.paymentCursor()).toBeNull();
    await b.page("payments");
    expect(request).toHaveBeenCalledTimes(2);
  });
  it("keeps loaded rows on page failure", async () => {
    const b = bank();
    b.payments.set([{ id } as any]);
    vi.spyOn(b, "request").mockRejectedValue(new HttpFailure(503, null));
    await expect(b.page("payments", true)).rejects.toThrow();
    expect(b.payments()[0].id).toBe(id);
  });
  it.each(["currency", "type", "created_at"])(
    "rejects a read update mutating %s",
    async (field) => {
      const b = bank(),
        notice = {
          id,
          payment_id: id,
          type: "PAYMENT_RECEIVED",
          currency: "AED",
          amount_minor: 10n,
          created_at: "2026-10-13T00:00:00Z",
          read_at: null,
        };
      b.notices.set([notice]);
      vi.spyOn(b, "request").mockResolvedValue({
        ...notice,
        [field]: "changed",
        read_at: "2026-10-13T01:00:00Z",
      });
      await b.readNotice(id);
      expect(b.notices()[0]).toEqual(notice);
      expect(b.message()).toContain("Invalid notification");
    },
  );
});
