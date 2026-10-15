import "@angular/compiler";
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
import { Bank } from "../src/app/core/bank";
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

async function rawRecord(store: Store, kind: string, write = false, value?: unknown, count = false): Promise<unknown> {
  const open = indexedDB.open("armoney-browser-v1", 1);
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error);
  });
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction("records", write ? "readwrite" : "readonly");
      const records = tx.objectStore("records");
      const key = store.scope(owner, kind);
      const request = write ? records.put(value, key) : count ? records.count(key) : records.get(key);
      tx.oncomplete = () => resolve(request.result);
      tx.onerror = tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
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
  it.each(["null", null, undefined])("preserves present corrupt pending record %s and blocks replacement and payment POST", async raw => {
    const s = new Store("https://null-pending-" + crypto.randomUUID());
    await rawRecord(s, "pending", true, raw);
    expect(await rawRecord(s, "pending", false, undefined, true)).toBe(1);
    await expect(s.pending(owner)).rejects.toThrow();
    await expect(s.create(owner, command)).rejects.toThrow();
    expect(await rawRecord(s, "pending")).toBe(raw);
    expect(await rawRecord(s, "pending", false, undefined, true)).toBe(1);
    vi.stubGlobal("sessionStorage", { getItem: () => null, setItem: () => {}, removeItem: () => {}, length: 0, key: () => null });
    const b = new Bank();
    Object.assign(b, { store: s, token: "x".repeat(43), expires: Date.now() + 1800000 });
    b.owner.set(owner);
    const request = vi.spyOn(b, "request").mockImplementation(async path => {
      if (path === "/v1/auth/me") return { id: owner };
      throw Error("Unexpected banking request");
    });
    const write = vi.spyOn(s, "write");
    const newKey = vi.spyOn(crypto, "randomUUID");
    await b.send(command);
    expect(request.mock.calls.some(([path]) => path === "/v1/payments")).toBe(false);
    expect(write).not.toHaveBeenCalled();
    expect(newKey).not.toHaveBeenCalled();
    expect(b.message()).toContain("invalid");
    expect(await rawRecord(s, "pending")).toBe(raw);
    expect(await rawRecord(s, "pending", false, undefined, true)).toBe(1);
    expect(await s.pending(other)).toBeNull();
  });
  it.each(["[]", "false", "0", '"text"', "{broken"])("rejects present non-record encoding %s without overwrite", async raw => {
    const s = new Store("https://invalid-shape-" + crypto.randomUUID());
    await rawRecord(s, "pending", true, raw);
    await expect(s.create(owner, command)).rejects.toThrow();
    expect(await rawRecord(s, "pending")).toBe(raw);
  });
  it("treats an actually missing record as absent and permits a first command", async () => {
    const s = new Store("https://missing-" + crypto.randomUUID());
    expect(await rawRecord(s, "pending")).toBeUndefined();
    expect(await rawRecord(s, "pending", false, undefined, true)).toBe(0);
    expect(await s.pending(owner)).toBeNull();
    expect(await s.cards(owner)).toEqual([]);
    const saved = await s.create(owner, command);
    expect(await s.pending(owner)).toEqual(saved);
  });
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
  it.each(["null", null, undefined])("preserves present corrupt card record %s and rejects a replacement", async raw => {
    const s = new Store("https://null-cards-" + crypto.randomUUID());
    await rawRecord(s, "cards", true, raw);
    expect(await rawRecord(s, "cards", false, undefined, true)).toBe(1);
    await expect(s.cards(owner)).rejects.toThrow();
    await expect(s.changeCard(owner, w, "VIRTUAL")).rejects.toThrow();
    expect(await rawRecord(s, "cards")).toBe(raw);
    expect(await rawRecord(s, "cards", false, undefined, true)).toBe(1);
    expect(await s.cards(other)).toEqual([]);
  });
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
