import { parse, stringify, LosslessNumber } from "lossless-json";
export const MAX = 9223372036854775807n;
export function decode(text: string): any {
  return parse(text, undefined, (n) =>
    /^-?\d+$/.test(n) ? BigInt(n) : new LosslessNumber(n),
  );
}
export function encode(value: unknown): string {
  return stringify(value) ?? "";
}
export function uuid(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      value,
    )
  )
    throw Error("Invalid identifier");
  return value;
}
export function minor(text: string): bigint {
  if (!/^\d+(?:\.\d{1,2})?$/.test(text))
    throw Error("Enter a positive AED amount with at most two decimals.");
  const [whole, fraction = ""] = text.split(".");
  const n = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  if (n < 1n || n > MAX) throw Error("Amount is outside the supported range.");
  return n;
}
export function money(n: bigint): string {
  return `AED ${n / 100n}.${(n % 100n).toString().padStart(2, "0")}`;
}
export interface Wallet {
  id: string;
  owner_id: string;
  currency: string;
  status: string;
  provisioning_status: string;
  ledger_account_id: string | null;
}
export const ready = (w: Wallet) =>
  w.currency === "AED" &&
  w.status === "ACTIVE" &&
  w.provisioning_status === "READY";
export interface Command {
  source_wallet_id: string;
  recipient_id: string;
  recipient_phone: string;
  currency: "AED";
  amount_minor: bigint;
}
export interface Pending {
  version: 1;
  origin: string;
  owner: string;
  key: string;
  command: Command;
  hash: string;
  attempted: boolean;
}
export interface Payment extends Command {
  id: string;
  requester_id: string;
  destination_wallet_id: string;
  status: "PENDING" | "COMPLETED" | "REJECTED";
  rejection_reason: string | null;
  created_at: string;
  updated_at: string;
}
export function payment(value: any, owner: string): Payment {
  uuid(value.id);
  uuid(value.requester_id);
  uuid(value.recipient_id);
  uuid(value.source_wallet_id);
  uuid(value.destination_wallet_id);
  if (
    value.currency !== "AED" ||
    typeof value.amount_minor !== "bigint" ||
    value.amount_minor < 1n ||
    value.amount_minor > MAX ||
    !["PENDING", "COMPLETED", "REJECTED"].includes(value.status) ||
    !(
      value.requester_id === owner ||
      (value.recipient_id === owner && value.status === "COMPLETED")
    ) ||
    (value.status === "REJECTED"
      ? !["INSUFFICIENT_FUNDS", "INVALID_ACCOUNT", "BALANCE_LIMIT"].includes(
          value.rejection_reason,
        )
      : value.rejection_reason !== null)
  )
    throw Error("Unexpected transfer response");
  return value;
}
export function matches(p: Payment, saved: Pending): boolean {
  return (
    p.requester_id === saved.owner &&
    Object.keys(saved.command).every(
      (k) => p[k as keyof Payment] === saved.command[k as keyof Command],
    )
  );
}
export async function hash(command: Command): Promise<string> {
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(encode(command)),
      ),
    ),
  )
    .map((v) => v.toString(16).padStart(2, "0"))
    .join("");
}
export interface Profile {
  id: string;
  identity_id: string;
  display_name: string;
  phone_number: string | null;
  phone_verified: boolean;
}
export interface Recipient {
  identity_id: string;
  display_name: string;
  phone_number: string;
}
export interface Notice {
  id: string;
  payment_id: string;
  type: "PAYMENT_COMPLETED" | "PAYMENT_REJECTED" | "PAYMENT_RECEIVED";
  currency: "AED";
  amount_minor: bigint;
  created_at: string;
  read_at: string | null;
}
