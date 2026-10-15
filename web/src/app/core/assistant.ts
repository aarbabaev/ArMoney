import { encode, MAX, uuid } from "./contracts";
export interface AssistantAnswer {
    reply: string;
    outcome: "answered" | "insufficient_evidence" | "empty_history" | "conflicting_evidence";
    citations: {
        document_id: string;
        version: string;
        section: string;
        excerpt: string;
    }[];
    facts: {
        wallets: {
            wallet_id: string;
            currency: "AED";
            balance_minor: bigint;
        }[] | null;
        last_transaction: {
            id: string;
            created_at: string;
            currency: "AED";
            amount_minor: bigint;
            direction: "incoming" | "outgoing";
            status: "PENDING" | "COMPLETED" | "REJECTED";
        } | null;
    };
    provenance: {
        source: string;
        fetched_at: string;
        scope: string;
    }[];
}
const invalid = () => { throw Error("Assistant returned an invalid answer. No live data could be confirmed."); };
function fields(value: any, names: string[]) {
    if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== names.length || names.some(n => !Object.hasOwn(value, n)))
        invalid();
}
function bounded(value: unknown, max: number) {
    if (typeof value !== "string" || !value.trim() || [...value].length > max || /[\uD800-\uDFFF]/u.test(value))
        invalid();
}
function instant(value: unknown) {
    if (typeof value !== "string" || !/(?:Z|[+-]\d{2}:\d{2})$/.test(value) || !Number.isFinite(Date.parse(value)))
        invalid();
}
export function assistantMessage(message: string) {
    if (!message.trim() || [...message].length > 2000 || /[\uD800-\uDFFF]/u.test(message))
        throw Error("Enter a question of up to 2,000 characters.");
    const body = { message };
    if (new TextEncoder().encode(encode(body)).length > 4096)
        throw Error("Question is too large. Shorten it and submit again.");
    return body;
}
export function assistantAnswer(value: any): AssistantAnswer {
    fields(value, ["reply", "outcome", "citations", "facts", "provenance"]);
    bounded(value.reply, 8000);
    if (!["answered", "insufficient_evidence", "empty_history", "conflicting_evidence"].includes(value.outcome) || !Array.isArray(value.citations) || value.citations.length > 6 || !Array.isArray(value.provenance) || value.provenance.length > 3)
        invalid();
    value.citations.forEach((c: any) => {
        fields(c, ["document_id", "version", "section", "excerpt"]);
        bounded(c.document_id, 128);
        bounded(c.version, 64);
        bounded(c.section, 256);
        bounded(c.excerpt, 1000);
    });
    const sources = new Set<string>();
    value.provenance.forEach((p: any) => {
        fields(p, ["source", "fetched_at", "scope"]);
        if (!["wallet-service", "payment-service", "policy-index"].includes(p.source) || sources.has(p.source))
            invalid();
        sources.add(p.source);
        instant(p.fetched_at);
        bounded(p.scope, 128);
    });
    fields(value.facts, ["wallets", "last_transaction"]);
    const wallets = value.facts.wallets;
    if (wallets !== null) {
        if (!Array.isArray(wallets) || wallets.length > 10 || !sources.has("wallet-service"))
            invalid();
        const ids = new Set<string>();
        wallets.forEach((w: any) => {
            fields(w, ["wallet_id", "currency", "balance_minor"]);
            uuid(w.wallet_id);
            if (ids.has(w.wallet_id) || w.currency !== "AED" || typeof w.balance_minor !== "bigint" || w.balance_minor < 0n || w.balance_minor > MAX)
                invalid();
            ids.add(w.wallet_id);
        });
    }
    const t = value.facts.last_transaction;
    if (t !== null) {
        fields(t, ["id", "created_at", "currency", "amount_minor", "direction", "status"]);
        uuid(t.id);
        instant(t.created_at);
        if (!sources.has("payment-service") || t.currency !== "AED" || typeof t.amount_minor !== "bigint" || t.amount_minor < 1n || t.amount_minor > MAX || !["incoming", "outgoing"].includes(t.direction) || !["PENDING", "COMPLETED", "REJECTED"].includes(t.status) || (t.direction === "incoming" && t.status !== "COMPLETED"))
            invalid();
    }
    if (value.citations.length && !sources.has("policy-index"))
        invalid();
    return value;
}
export function assistantTime(value: string) {
    return new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Dubai", dateStyle: "medium", timeStyle: "medium" }).format(new Date(value)) + " Asia/Dubai";
}
