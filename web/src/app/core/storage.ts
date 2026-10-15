import {
  Command,
  Pending,
  Wallet,
  ready,
  uuid,
  hash,
  encode,
  decode,
  MAX,
} from "./contracts";
export interface DemoCard {
  id: string;
  walletId: string;
  type: "VIRTUAL" | "PHYSICAL";
  status: "ACTIVE" | "FROZEN" | "CLOSED";
  createdAt: string;
}
const request = <T>(r: IDBRequest<T>) =>
  new Promise<T>((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(Error("Local storage is unavailable."));
  });
/** No credential enters this database. A corrupt record is preserved and blocks commands. */
export class Store {
  private db = new Promise<IDBDatabase>((resolve, reject) => {
    const r = indexedDB.open("armoney-browser-v1", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("records");
    r.onsuccess = () => resolve(r.result);
    r.onerror = () =>
      reject(Error("Local storage unavailable; transfers disabled."));
  });
  constructor(readonly origin = location.origin) {}
  scope(owner: string, kind: string) {
    return `${this.origin}|${uuid(owner)}|${kind}`;
  }
  async read(owner: string, kind: string): Promise<any> {
    const db = await this.db;
    const records = db.transaction("records").objectStore("records");
    const key = this.scope(owner, kind);
    // Queue both requests before awaiting so presence and content come from
    // the same snapshot, including records whose stored value is undefined.
    const valueRequest = records.get(key);
    const keyRequest = records.getKey(key);
    const [raw, presentKey] = await Promise.all([request(valueRequest), request(keyRequest)]);
    if (presentKey === undefined) return null;
    try {
      if (typeof raw !== "string") throw Error();
      const record = decode(raw);
      if (record === null || typeof record !== "object" ||
          Object.getPrototypeOf(record) !== Object.prototype) throw Error();
      return record;
    } catch {
      throw Error("Saved local record is invalid; saved data preserved.");
    }
  }
  async write(owner: string, kind: string, value: unknown): Promise<void> {
    const db = await this.db;
    return new Promise((resolve, reject) => {
      const tx = db.transaction("records", "readwrite");
      tx.objectStore("records").put(encode(value), this.scope(owner, kind));
      tx.oncomplete = () => resolve();
      tx.onerror = tx.onabort = () =>
        reject(Error("Local storage write failed; transfer not sent."));
    });
  }
  async remove(owner: string, kind: string): Promise<void> {
    const db = await this.db;
    return new Promise((resolve, reject) => {
      const tx = db.transaction("records", "readwrite");
      tx.objectStore("records").delete(this.scope(owner, kind));
      tx.oncomplete = () => resolve();
      tx.onerror = tx.onabort = () =>
        reject(Error("Local storage update failed."));
    });
  }
  async lock<T>(owner: string, action: () => Promise<T>): Promise<T> {
    if (!navigator.locks)
      throw Error(
        "This browser cannot safely coordinate transfers across tabs.",
      );
    return navigator.locks.request(this.scope(owner, "lock"), action);
  }
  async pending(owner: string): Promise<Pending | null> {
    const p = await this.read(owner, "pending");
    if (p === null) return null;
    const c = p.command;
    if (
      p.version !== 1n ||
      p.origin !== this.origin ||
      p.owner !== owner ||
      !/^[A-Za-z0-9_-]{1,128}$/.test(p.key) ||
      typeof p.attempted !== "boolean" ||
      !c ||
      c.currency !== "AED" ||
      typeof c.amount_minor !== "bigint" ||
      c.amount_minor < 1n ||
      c.amount_minor > MAX ||
      typeof c.recipient_phone !== "string" ||
      Object.keys(c).sort().join(",") !==
        "amount_minor,currency,recipient_id,recipient_phone,source_wallet_id" ||
      p.hash !== (await hash(c))
    )
      throw Error(
        "Saved transfer is invalid. Payments disabled; do not create a replacement.",
      );
    uuid(c.source_wallet_id);
    uuid(c.recipient_id);
    return { ...p, version: 1 };
  }
  async create(owner: string, command: Command): Promise<Pending> {
    if (await this.pending(owner))
      throw Error("Resolve your saved transfer first.");
    const p: Pending = {
      version: 1,
      origin: this.origin,
      owner,
      key: crypto.randomUUID(),
      command,
      hash: await hash(command),
      attempted: false,
    };
    await this.write(owner, "pending", p);
    return p;
  }
  async cards(owner: string): Promise<DemoCard[]> {
    const record = await this.read(owner, "cards");
    if (record === null) return [];
    if (
      record.version !== 1n ||
      record.origin !== this.origin ||
      record.owner !== owner ||
      !Array.isArray(record.cards) ||
      record.cards.length > 1000
    )
      throw Error("Demo card storage invalid; saved data preserved.");
    const ids = new Set(),
      live = new Set();
    for (const c of record.cards) {
      uuid(c.id);
      uuid(c.walletId);
      const pair = c.walletId + "|" + c.type;
      if (
        ids.has(c.id) ||
        !["VIRTUAL", "PHYSICAL"].includes(c.type) ||
        !["ACTIVE", "FROZEN", "CLOSED"].includes(c.status) ||
        !Number.isFinite(Date.parse(c.createdAt)) ||
        (c.status !== "CLOSED" && live.has(pair))
      )
        throw Error("Demo card storage invalid; saved data preserved.");
      ids.add(c.id);
      if (c.status !== "CLOSED") live.add(pair);
    }
    return record.cards;
  }
  async changeCard(
    owner: string,
    wallet: Wallet | null,
    type: DemoCard["type"],
    id?: string,
    status?: DemoCard["status"],
  ) {
    return this.lock(owner, async () => {
      const cards = await this.cards(owner);
      if (id) {
        const c = cards.find((c) => c.id === id);
        if (!c || !status || c.status === "CLOSED")
          throw Error("Closed cards cannot be changed.");
        c.status = status;
      } else {
        if (!wallet || wallet.owner_id !== owner || !ready(wallet))
          throw Error("Choose an active ready account.");
        if (
          !cards.some(
            (c) =>
              c.walletId === wallet.id &&
              c.type === type &&
              c.status !== "CLOSED",
          )
        ) {
          if (cards.length >= 1000)
            throw Error("Demo card storage limit reached.");
          cards.push({
            id: crypto.randomUUID(),
            walletId: wallet.id,
            type,
            status: "ACTIVE",
            createdAt: new Date().toISOString(),
          });
        }
      }
      await this.write(owner, "cards", {
        version: 1,
        origin: this.origin,
        owner,
        cards,
      });
      return cards;
    });
  }
}
