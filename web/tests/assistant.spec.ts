import "@angular/compiler";
import "fake-indexeddb/auto";
import { beforeEach, expect, it, vi } from "vitest";
import { Bank } from "../src/app/core/bank";
import { assistantAnswer, assistantMessage } from "../src/app/core/assistant";
import { decode, encode } from "../src/app/core/contracts";
const owner = "11111111-1111-4111-8111-111111111111";
const answer = () => ({ reply: "Available balance", outcome: "answered", citations: [], facts: { wallets: [{ wallet_id: owner, currency: "AED", balance_minor: 9223372036854775807n }], last_transaction: null }, provenance: [{ source: "wallet-service", fetched_at: "2026-10-14T00:00:00Z", scope: "owned active wallets" }] });
beforeEach(() => { vi.stubGlobal("location", { origin: "https://localhost:4200", protocol: "https:" }); vi.stubGlobal("sessionStorage", { getItem: () => null, setItem: () => { }, removeItem: () => { }, length: 0, key: () => null }); });
function bank() { const b = new Bank(); b.owner.set(owner); Object.assign(b, { token: "a".repeat(43), expires: Date.now() + 100000 }); vi.spyOn((b as any).oidc, "removeUser").mockResolvedValue(undefined); vi.spyOn((b as any).oidc, "signoutRedirect").mockResolvedValue(undefined); return b; }
const response = (value: unknown, status = 200) => new Response(encode(value), { status, headers: { "content-type": "application/json" } });
it("sends only message/current bearer and preserves Int64 facts", async () => { const b = bank(); const f = vi.fn().mockResolvedValue(response(answer())); vi.stubGlobal("fetch", f); await b.askAssistant("balance"); expect(f).toHaveBeenCalledOnce(); const [path, options] = f.mock.calls[0]; expect(path).toBe("/v1/assistant/messages"); expect(options.method).toBe("POST"); expect(options.body).toBe('{"message":"balance"}'); expect(options.headers.Authorization).toBe("Bearer " + "a".repeat(43)); expect(options.redirect).toBe("error"); expect(options.cache).toBe("no-store"); expect(b.assistant().answer?.facts.wallets?.[0].balance_minor).toBe(9223372036854775807n); });
it("rejects malformed facts, missing provenance, duplicate wallets and unknown fields", () => { for (const mutate of [(a: any) => a.facts.wallets[0].balance_minor = "1", (a: any) => a.facts.wallets[0].balance_minor = 9223372036854775808n, (a: any) => a.facts.wallets[0].currency = "USD", (a: any) => a.provenance = [], (a: any) => a.facts.wallets.push(a.facts.wallets[0]), (a: any) => a.owner = owner, (a: any) => a.provenance[0].fetched_at = "yesterday"]) {
    const a = answer();
    mutate(a);
    expect(() => assistantAnswer(a)).toThrow();
} expect(() => assistantAnswer(decode('{"reply":"a","reply":"b"}'))).toThrow(); });
it("validates Unicode and encoded body bounds before sending", async () => { for (const value of [" ", "x".repeat(2001), "😀".repeat(1100), "\ud800"])
    expect(() => assistantMessage(value)).toThrow(); expect(assistantMessage("😀".repeat(500)).message).toHaveLength(1000); const b = bank(); vi.stubGlobal("fetch", vi.fn()); await b.askAssistant(" "); expect(fetch).not.toHaveBeenCalled(); });
it.each([400, 413, 429, 503])("shows HTTP %s without retry or invented answer", async (status) => { const b = bank(); vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response({ error: "service_unavailable" }, status))); await b.askAssistant("balance"); expect(fetch).toHaveBeenCalledOnce(); expect(b.assistant().answer).toBeNull(); expect(b.assistant().error).not.toBe(""); });
it("clears session and assistant on 401", async () => { const b = bank(); vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response({ error: "invalid_credentials" }, 401))); await b.askAssistant("balance"); expect(b.owner()).toBeNull(); expect(b.assistant()).toEqual({ loading: false, question: "", answer: null, error: "" }); });
it("ignores held answers after logout/new owner and prevents concurrent turns", async () => { const b = bank(); let release!: (v: Response) => void; const held = new Promise<Response>(r => release = r); vi.stubGlobal("fetch", vi.fn().mockImplementation(path => path === "/v1/auth/logout" ? Promise.resolve(new Response(null, { status: 204 })) : held)); const pending = b.askAssistant("balance"); await b.askAssistant("again"); expect(fetch).toHaveBeenCalledOnce(); await b.logout(); b.owner.set("22222222-2222-4222-8222-222222222222"); release(response(answer())); await pending; expect(b.assistant()).toEqual({ loading: false, question: "", answer: null, error: "" }); });
it("keeps empty history explicit and rejects pending incoming transactions", () => { const a: any = answer(); a.facts.wallets = null; a.outcome = "empty_history"; expect(assistantAnswer(a).outcome).toBe("empty_history"); a.provenance[0].source = "payment-service"; a.facts.last_transaction = { id: owner, created_at: "2026-10-14T00:00:00Z", currency: "AED", amount_minor: 1n, direction: "incoming", status: "PENDING" }; expect(() => assistantAnswer(a)).toThrow(); });
it("treats oversized/malformed responses and transport failure as unavailable", async () => { for (const result of [response({ ...answer(), reply: "x".repeat(33000) }), response({}), null]) {
    const b = bank();
    vi.stubGlobal("fetch", result ? vi.fn().mockResolvedValue(result) : vi.fn().mockRejectedValue(Error("network")));
    await b.askAssistant("balance");
    expect(b.assistant().answer).toBeNull();
    expect(b.assistant().error).toContain("unavailable");
} });
it("clears an answer if the session expires while the request is in flight", async () => { const b = bank(); vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => { Object.assign(b, { expires: Date.now() - 1 }); return response(answer()); })); await b.askAssistant("balance"); expect(b.owner()).toBeNull(); expect(b.assistant().answer).toBeNull(); });
