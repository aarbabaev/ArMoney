import { Injectable, signal } from "@angular/core";
import {
  UserManager,
  WebStorageStateStore,
  InMemoryWebStorage,
} from "oidc-client-ts";
import {
  decode,
  encode,
  uuid,
  Wallet,
  Payment,
  Pending,
  Command,
  ready,
  payment,
  matches,
  money,
  Profile,
  Recipient,
  Notice,
  MAX,
} from "./contracts";
import { Store, DemoCard } from "./storage";
import { issuer } from "../../environment";
import { AssistantAnswer, assistantAnswer, assistantMessage } from "./assistant";
export class HttpFailure extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
  ) {
    super(
      status === 429
        ? "Too many requests. Please wait before retrying."
        : `Request could not be confirmed (HTTP ${status}).`,
    );
  }
}
@Injectable({ providedIn: "root" })
export class Bank {
  readonly assistant = signal<{ loading: boolean; question: string; answer: AssistantAnswer | null; error: string }>({ loading: false, question: "", answer: null, error: "" });
  readonly owner = signal<string | null>(null);
  readonly busy = signal(false);
  readonly message = signal("");
  readonly wallets = signal<Wallet[]>([]);
  readonly balances = signal<Record<string, bigint | null>>({});
  readonly profile = signal<Profile | null>(null);
  readonly recipient = signal<Recipient | null>(null);
  readonly pending = signal<Pending | null>(null);
  readonly payments = signal<Payment[]>([]);
  readonly notices = signal<Notice[]>([]);
  readonly cards = signal<DemoCard[]>([]);
  readonly detail = signal<Payment | null>(null);
  readonly blocked = signal(false);
  readonly cardError = signal("");
  readonly paymentCursor = signal<string | null>(null);
  readonly noticeCursor = signal<string | null>(null);
  private pageGeneration = { payments: 0, notifications: 0 };
  private pageLoaded = { payments: false, notifications: false };
  private pageBusy = { payments: false, notifications: false };
  private token: string | null = null;
  private expires = 0;
  private generation = 0;
  private operation = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  readonly store = new Store();
  money = money;
  ready = ready;
  private oidc = new UserManager({
    authority: issuer(),
    client_id: "armoney-web",
    redirect_uri: location.origin + "/oauth/callback",
    post_logout_redirect_uri: location.origin + "/oauth/logout",
    response_type: "code",
    scope: "openid profile",
    disablePKCE: false,
    automaticSilentRenew: false,
    loadUserInfo: false,
    staleStateAgeInSeconds: 600,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: sessionStorage }),
  });
  async task(action: () => Promise<void>) {
    if (this.busy()) return;
    const ticket = ++this.operation;
    this.busy.set(true);
    this.message.set("");
    try {
      await action();
    } catch (e) {
      if (ticket !== this.operation) return;
      this.message.set(
        e instanceof Error
          ? e.message
          : "Unable to confirm request. Saved transfers are preserved.",
      );
    } finally {
      if (ticket === this.operation) this.busy.set(false);
    }
  }
  private clear() {
    this.assistant.set({ loading: false, question: "", answer: null, error: "" });
    this.generation++;
    this.operation++;
    this.busy.set(false);
    this.pageGeneration.payments++;
    this.pageGeneration.notifications++;
    this.pageLoaded = { payments: false, notifications: false };
    this.pageBusy = { payments: false, notifications: false };
    clearTimeout(this.timer);
    this.token = null;
    this.owner.set(null);
    this.wallets.set([]);
    this.balances.set({});
    this.profile.set(null);
    this.recipient.set(null);
    this.pending.set(null);
    this.payments.set([]);
    this.notices.set([]);
    this.cards.set([]);
    this.detail.set(null);
    this.paymentCursor.set(null);
    this.noticeCursor.set(null);
    this.blocked.set(false);
    this.cardError.set("");
  }
  async request(
    path: string,
    method = "GET",
    body?: unknown,
    key?: string,
    token = this.token,
    credentialExpiry = this.expires,
  ): Promise<any> {
    if (!path.startsWith("/v1/") || path.startsWith("//"))
      throw Error("Invalid API path");
    if (token && Date.now() >= credentialExpiry) {
      this.clear();
      throw Error(
        "Session expired. Sign in again; saved transfers are preserved.",
      );
    }
    const epoch = this.generation;
    const response = await fetch(path, {
      method,
      credentials: "omit",
      redirect: "error",
      cache: "no-store",
      headers: {
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        ...(token ? { Authorization: "Bearer " + token } : {}),
        ...(key ? { "Idempotency-Key": key } : {}),
      },
      body: body === undefined ? undefined : encode(body),
      signal: AbortSignal.timeout(path === "/v1/assistant/messages" ? 40000 : 15000),
    });
    const text = await response.text();
    if (token && epoch !== this.generation)
      throw Error("Session changed; saved transfer is preserved.");
    if (text.length > 1048576) throw Error("Response too large");
    if (path === "/v1/assistant/messages" && new TextEncoder().encode(text).length > 32768) throw Error("Assistant response too large");
    if (!response.ok) {
      if (response.status === 401 && epoch === this.generation) this.clear();
      const exact =
        response.headers.get("content-type")?.split(";")[0] ===
          "application/json" && text.length <= 4096
          ? (/^\s*\{\s*"error"\s*:\s*"([a-z_]+)"\s*}\s*$/.exec(text)?.[1] ??
            null)
          : null;
      throw new HttpFailure(response.status, exact);
    }
    if (response.status === 204) return null;
    if (!response.headers.get("content-type")?.startsWith("application/json"))
      throw Error("Unexpected server response");
    return decode(text);
  }
  async askAssistant(question: string) {
    if (this.assistant().loading || !this.owner()) return;
    const epoch = this.generation;
    this.assistant.set({ loading: true, question, answer: null, error: "" });
    try {
      const body = assistantMessage(question);
      const answer = assistantAnswer(await this.request("/v1/assistant/messages", "POST", body));
      if (epoch !== this.generation) return;
      if (Date.now() >= this.expires) {
        this.clear();
        this.message.set("Session expired. Sign in again.");
        return;
      }
      this.assistant.set({ loading: false, question, answer, error: "" });
    } catch (e) {
      if (epoch !== this.generation) return;
      const error = e instanceof HttpFailure
        ? e.status === 429 ? "Assistant is busy. Please wait before submitting again."
          : e.status === 400 || e.status === 413 ? "Question could not be accepted. Shorten or edit it and submit again."
          : "Assistant unavailable. No answer or live data could be confirmed."
        : e instanceof Error && (e.message.startsWith("Enter a question") || e.message.startsWith("Question is too large")) ? e.message
          : "Assistant unavailable. No answer or live data could be confirmed. Submit again when ready.";
      this.assistant.set({ loading: false, question, answer: null, error });
    }
  }
  signIn() {
    return this.task(async () => {
      if (location.protocol !== "https:")
        throw Error("Secure HTTPS hosting is required.");
      await this.oidc.signinRedirect({ nonce: crypto.randomUUID() });
    });
  }
  callback() {
    return this.task(async () => {
      const epoch = this.generation;
      try {
        const user = await this.oidc.signinRedirectCallback();
        if (epoch !== this.generation) return;
        const session = await this.request("/v1/auth/sso", "POST", {
          access_token: user.access_token,
        });
        if (epoch !== this.generation) return;
        const expires = Date.parse(session.expires_at);
        if (
          !/^[A-Za-z0-9_-]{43}$/.test(session.access_token) ||
          session.token_type !== "Bearer" ||
          !Number.isFinite(expires) ||
          expires <= Date.now()
        )
          throw Error("Invalid bank session");
        const identity = await this.request(
          "/v1/auth/me",
          "GET",
          undefined,
          undefined,
          session.access_token,
          expires,
        );
        uuid(identity.id);
        if (epoch !== this.generation) return;
        this.generation++;
        this.expires = expires;
        this.token = session.access_token;
        this.owner.set(identity.id);
        this.timer = setTimeout(() => {
          this.clear();
          this.message.set(
            "Session expired. Sign in again; saved transfers are preserved.",
          );
        }, this.expires - Date.now());
        history.replaceState(null, "", "/home");
        await this.refresh();
      } finally {
        await this.oidc.removeUser();
      }
    });
  }
  async logout() {
    const token = this.token;
    this.clear();
    const epoch = this.generation;
    try {
      if (token)
        await this.request(
          "/v1/auth/logout",
          "POST",
          undefined,
          undefined,
          token,
        );
    } catch {
      if (epoch === this.generation)
        this.message.set(
          "Local sign-out complete; server revocation could not be confirmed.",
        );
    }
    if (epoch !== this.generation) return;
    await this.oidc.removeUser();
    if (epoch !== this.generation) return;
    try {
      await this.oidc.signoutRedirect();
    } catch {
      if (epoch === this.generation)
        this.message.set(
          "Signed out locally. Provider sign-out could not be opened.",
        );
    }
  }

  async verified() {
    const epoch = this.generation,
      owner = this.owner();
    if (!owner) throw Error("Sign in first.");
    const identity = await this.request("/v1/auth/me");
    if (epoch !== this.generation)
      throw Error("Session changed; sign in again.");
    if (identity.id !== owner) {
      this.clear();
      throw Error("Identity changed; sign in again.");
    }
    return owner;
  }
  async refresh() {
    const epoch = this.generation;
    const owner = await this.verified();
    try {
      const pending = await this.store.pending(owner);
      if (epoch !== this.generation) return;
      this.pending.set(pending);
      this.blocked.set(false);
    } catch (e) {
      if (epoch === this.generation) this.blocked.set(true);
      throw e;
    }
    const data = await this.request("/v1/wallets");
    if (
      !Array.isArray(data.wallets) ||
      data.wallets.some(
        (w: Wallet) => w.owner_id !== owner || w.currency !== "AED",
      )
    )
      throw Error("Unexpected wallet owner or currency");
    data.wallets.forEach((w: Wallet) => uuid(w.id));
    if (epoch !== this.generation) return;
    this.wallets.set(data.wallets);
    const balances: Record<string, bigint | null> = {};
    for (const w of data.wallets.filter(ready)) {
      try {
        const b = await this.request(`/v1/wallets/${w.id}/balance`);
        if (
          b.wallet_id !== w.id ||
          b.currency !== "AED" ||
          typeof b.balance_minor !== "bigint" ||
          b.balance_minor < 0n ||
          b.balance_minor > MAX
        )
          throw Error("Invalid balance");
        balances[w.id] = b.balance_minor;
      } catch {
        balances[w.id] = null;
      }
    }
    if (epoch !== this.generation) return;
    this.balances.set(balances);
    for (const resource of ["payments", "notifications"] as const) {
      try {
        await this.page(resource, true);
      } catch (e) {
        if (epoch === this.generation)
          this.message.set(
            e instanceof Error ? e.message : "History unavailable.",
          );
      }
      if (epoch !== this.generation) return;
    }
    let profile;
    try {
      profile = await this.request("/v1/users/me");
      if (profile.identity_id !== owner)
        throw Error("Unexpected profile owner");
    } catch (e) {
      if (e instanceof HttpFailure && e.status === 404) profile = null;
      else throw e;
    }
    if (epoch !== this.generation) return;
    this.profile.set(profile);
    try {
      const cards = await this.store.cards(owner);
      if (epoch !== this.generation) return;
      this.cards.set(cards);
      this.cardError.set("");
    } catch {
      if (epoch !== this.generation) return;
      this.cardError.set(
        "Demo card storage is invalid or unavailable. Saved data is preserved.",
      );
    }
  }
  async page(resource: "payments" | "notifications", refresh = false) {
    const epoch = this.generation,
      owner = this.owner();
    if (!owner) return;
    const cursor =
      resource === "payments" ? this.paymentCursor : this.noticeCursor;
    const rows = resource === "payments" ? this.payments : this.notices;
    if (
      !refresh &&
      (this.pageBusy[resource] ||
        (this.pageLoaded[resource] && cursor() === null))
    )
      return;
    const generation = ++this.pageGeneration[resource];
    this.pageBusy[resource] = true;
    try {
      const result = await this.request(
        `/v1/${resource}/page?limit=30${!refresh && cursor() ? "&cursor=" + encodeURIComponent(cursor()!) : ""}`,
      );
      if (
        !Array.isArray(result[resource]) ||
        (result.next_cursor !== null &&
          (typeof result.next_cursor !== "string" ||
            !/^[A-Za-z0-9_-]{1,512}$/.test(result.next_cursor)))
      )
        throw Error("Invalid history page");
      const items = result[resource];
      if (resource === "payments") items.forEach((p: any) => payment(p, owner));
      else
        items.forEach((n: any) => {
          uuid(n.id);
          uuid(n.payment_id);
          if (
            n.currency !== "AED" ||
            typeof n.amount_minor !== "bigint" ||
            n.amount_minor < 1n ||
            n.amount_minor > MAX ||
            ![
              "PAYMENT_COMPLETED",
              "PAYMENT_REJECTED",
              "PAYMENT_RECEIVED",
            ].includes(n.type)
          )
            throw Error("Invalid notification");
        });
      if (
        epoch !== this.generation ||
        generation !== this.pageGeneration[resource]
      )
        return;
      rows.set([
        ...new Map(
          [...(refresh ? [] : rows()), ...items].map((p) => [p.id, p]),
        ).values(),
      ]);
      cursor.set(result.next_cursor);
      this.pageLoaded[resource] = true;
    } catch (e) {
      if (
        epoch !== this.generation ||
        generation !== this.pageGeneration[resource]
      )
        return;
      if (e instanceof HttpFailure && e.code === "invalid_page_request")
        cursor.set(null);
      throw e;
    } finally {
      if (generation === this.pageGeneration[resource])
        this.pageBusy[resource] = false;
    }
  }

  openWallet() {
    return this.task(async () => {
      await this.request("/v1/wallets", "POST", { currency: "AED" });
      await this.refresh();
    });
  }
  resolve(phone: string) {
    return this.task(async () => {
      this.recipient.set(null);
      if (!/^\+9715[024568][0-9]{7}$/.test(phone))
        throw Error("Enter a UAE mobile number, for example +971501234567.");
      const epoch = this.generation;
      const r = await this.request("/v1/recipients/resolve", "POST", {
        phone_number: phone,
      });
      uuid(r.identity_id);
      if (r.phone_number !== phone || r.identity_id === this.owner())
        throw Error("Recipient could not be confirmed.");
      if (epoch === this.generation) this.recipient.set(r);
    });
  }
  send(command?: Command) {
    return this.task(async () => {
      if (this.blocked()) throw Error("Local recovery storage is blocked.");
      const epoch = this.generation;
      const owner = await this.verified();
      if (epoch !== this.generation) return;
      await this.store.lock(owner, async () => {
        if (epoch !== this.generation) return;
        let saved = await this.store.pending(owner);
        if (command) {
          if (saved)
            throw Error("Resolve the saved transfer before starting another.");
          saved = await this.store.create(owner, command);
        }
        if (!saved) throw Error("No saved transfer");
        if (epoch !== this.generation) return;
        this.pending.set(saved);
        const attempted = { ...saved, attempted: true };
        await this.store.write(owner, "pending", attempted);
        if (epoch !== this.generation) return;
        this.pending.set(attempted);
        const result = payment(
          await this.request("/v1/payments", "POST", saved.command, saved.key),
          owner,
        );
        if (!matches(result, saved))
          throw Error("Transfer response does not match saved command.");
        if (epoch !== this.generation) return;
        if (result.status !== "PENDING")
          await this.store.remove(owner, "pending");
        if (epoch !== this.generation) return;
        this.detail.set(result);
        this.pending.set(result.status === "PENDING" ? attempted : null);
        this.message.set(
          result.status === "COMPLETED"
            ? "Transfer completed."
            : result.status === "REJECTED"
              ? "Transfer rejected: " + result.rejection_reason
              : "Transfer pending ledger confirmation.",
        );
      });
      if (epoch === this.generation) await this.refresh();
    });
  }
  details(id: string) {
    return this.task(async () => {
      const epoch = this.generation,
        owner = this.owner();
      if (!owner) return;
      const p = payment(await this.request("/v1/payments/" + uuid(id)), owner);
      if (p.id !== id) throw Error("Invalid transfer detail");
      if (epoch === this.generation) this.detail.set(p);
    });
  }
  readNotice(id: string) {
    return this.task(async () => {
      const epoch = this.generation;
      const old = this.notices().find((n) => n.id === id);
      const n = await this.request(
        "/v1/notifications/" + uuid(id) + "/read",
        "POST",
      );
      if (
        !old ||
        n.id !== old.id ||
        n.payment_id !== old.payment_id ||
        !n.read_at ||
        n.amount_minor !== old.amount_minor ||
        n.currency !== old.currency ||
        n.type !== old.type ||
        n.created_at !== old.created_at
      )
        throw Error("Invalid notification update");
      if (epoch === this.generation)
        this.notices.update((items) => items.map((i) => (i.id === id ? n : i)));
    });
  }
  saveName(display_name: string) {
    return this.task(async () => {
      if (!display_name.trim() || display_name.trim().length > 100)
        throw Error("Enter a name of 1–100 characters.");
      await this.request("/v1/users/me", "PUT", {
        display_name: display_name.trim(),
      });
      await this.refresh();
    });
  }
  card(
    wallet: Wallet | null,
    type: DemoCard["type"],
    id?: string,
    status?: DemoCard["status"],
  ) {
    return this.task(async () => {
      if (this.cardError()) throw Error(this.cardError());
      const epoch = this.generation,
        owner = await this.verified();
      const cards = await this.store.changeCard(
        owner,
        wallet,
        type,
        id,
        status,
      );
      if (epoch === this.generation) this.cards.set(cards);
    });
  }
}
