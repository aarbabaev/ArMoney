import { describe, it, expect, beforeEach, vi } from "vitest";
import "fake-indexeddb/auto";
import {
  decode,
  encode,
  minor,
  money,
  MAX,
  payment,
  hash,
} from "../src/app/core/contracts";
import { Store } from "../src/app/core/storage";
const owner = "11111111-1111-4111-8111-111111111111",
  other = "22222222-2222-4222-8222-222222222222",
  wallet = "33333333-3333-4333-8333-333333333333";
const command = {
  source_wallet_id: wallet,
  recipient_id: other,
  recipient_phone: "+971501234567",
  currency: "AED" as const,
  amount_minor: MAX,
};
beforeEach(() => {
  vi.stubGlobal("location", { origin: "https://localhost:4200" });
  const queues = new Map<string, Promise<unknown>>();
  vi.stubGlobal("navigator", {
    locks: {
      request: async (name: string, action: () => Promise<unknown>) => {
        const previous = queues.get(name) ?? Promise.resolve();
        const next = previous.catch(() => {}).then(action);
        queues.set(name, next);
        return next;
      },
    },
  });
});
describe("exact AED contract", () => {
  it("round trips Java long maximum without a Number conversion", () => {
    expect(decode('{"amount_minor":9223372036854775807}').amount_minor).toBe(
      MAX,
    );
    expect(encode(command)).toContain("9223372036854775807");
    expect(minor("92233720368547758.07")).toBe(MAX);
    expect(money(MAX)).toBe("AED 92233720368547758.07");
  });
  it.each(["0", "-1", "1.001", "1e3", "NaN", "92233720368547758.08"])(
    "rejects invalid money %s",
    (value) => expect(() => minor(value)).toThrow(),
  );
  it("rejects foreign and fabricated terminal success", () => {
    expect(() =>
      payment(
        {
          ...command,
          id: wallet,
          requester_id: other,
          destination_wallet_id: wallet,
          status: "COMPLETED",
          rejection_reason: null,
        },
        owner,
      ),
    ).toThrow();
    expect(() =>
      payment(
        {
          ...command,
          id: wallet,
          requester_id: owner,
          destination_wallet_id: wallet,
          status: "SUCCESS",
          rejection_reason: null,
        },
        owner,
      ),
    ).toThrow();
  });
});
describe("durable owner recovery", () => {
  it("preserves exact attempted command across reload and other identities", async () => {
    const store = new Store("https://recovery-" + crypto.randomUUID());
    const saved = await store.lock(owner, () => store.create(owner, command));
    await store.write(owner, "pending", { ...saved, attempted: true });
    const reload = new Store(store.origin);
    expect(await reload.pending(other)).toBeNull();
    expect(await reload.pending(owner)).toEqual({ ...saved, attempted: true });
    expect(await new Store(store.origin + "-other").pending(owner)).toBeNull();
  });
  it("serializes two tabs creating commands, preventing replacement", async () => {
    const origin = "https://tabs-" + crypto.randomUUID(),
      a = new Store(origin),
      b = new Store(origin);
    const outcomes = await Promise.allSettled([
      a.lock(owner, () => a.create(owner, command)),
      b.lock(owner, () => b.create(owner, { ...command, amount_minor: 1n })),
    ]);
    expect(outcomes.filter((o) => o.status === "fulfilled")).toHaveLength(1);
    expect(outcomes.filter((o) => o.status === "rejected")).toHaveLength(1);
  });
  it("fails closed on tampering without overwriting saved evidence", async () => {
    const s = new Store("https://corrupt-" + crypto.randomUUID());
    const p = await s.create(owner, command);
    await s.write(owner, "pending", {
      ...p,
      command: { ...command, amount_minor: 1n },
    });
    await expect(s.pending(owner)).rejects.toThrow("invalid");
    await expect(s.create(owner, command)).rejects.toThrow();
    expect((await s.read(owner, "pending")).command.amount_minor).toBe(1n);
  });
  it("blocks a POST when the durable attempt marker cannot be written", async () => {
    const s = new Store("https://marker-" + crypto.randomUUID());
    const p = await s.create(owner, command);
    expect(p.attempted).toBe(false);
    expect(p.hash).toBe(await hash(command));
  });
});
describe("owner demonstration cards", () => {
  const w = {
    id: wallet,
    owner_id: owner,
    currency: "AED",
    status: "ACTIVE",
    provisioning_status: "READY",
    ledger_account_id: wallet,
  };
  it("allows one live type per wallet and keeps closure terminal", async () => {
    const s = new Store("https://cards-" + crypto.randomUUID());
    const first = await s.changeCard(owner, w, "VIRTUAL");
    expect(await s.changeCard(owner, w, "VIRTUAL")).toHaveLength(1);
    await s.changeCard(owner, null, "VIRTUAL", first[0].id, "FROZEN");
    await s.changeCard(owner, null, "VIRTUAL", first[0].id, "CLOSED");
    await expect(
      s.changeCard(owner, null, "VIRTUAL", first[0].id, "ACTIVE"),
    ).rejects.toThrow();
    expect(await s.cards(other)).toEqual([]);
    expect(await s.changeCard(owner, w, "VIRTUAL")).toHaveLength(2);
  });
  it("preserves corruption and rejects capacity before write", async () => {
    const s = new Store("https://capacity-" + crypto.randomUUID());
    const cards = Array.from({ length: 1000 }, () => ({
      id: crypto.randomUUID(),
      walletId: wallet,
      type: "VIRTUAL",
      status: "CLOSED",
      createdAt: new Date().toISOString(),
    }));
    await s.write(owner, "cards", {
      version: 1,
      origin: s.origin,
      owner,
      cards,
    });
    await expect(s.changeCard(owner, w, "PHYSICAL")).rejects.toThrow("limit");
    expect(await s.cards(owner)).toHaveLength(1000);
    await s.write(owner, "cards", {
      version: 1,
      origin: s.origin,
      owner,
      cards: [{ ...cards[0], status: "BROKEN" }],
    });
    await expect(s.changeCard(owner, w, "VIRTUAL")).rejects.toThrow("invalid");
    expect((await s.read(owner, "cards")).cards[0].status).toBe("BROKEN");
  });
});
