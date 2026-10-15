package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.VerificationStore;
import com.arman.bank.authservice.domain.Verification.*;
import com.arman.bank.authservice.domain.Verification.Error;
import com.arman.bank.runtime.Database;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.jooq.DSLContext;
import org.jooq.Record;

/** All core mutations use one short database transaction; no provider I/O under locks.
 * The singleton lock deliberately serializes the bounded initial rollout, including
 * capacity and issuance budgets. It is not a production throughput claim. */
public final class PostgresVerificationStore implements VerificationStore {
    private final Database database;
    private final VerificationCrypto crypto;
    public PostgresVerificationStore(Database database, VerificationCrypto crypto) {
        this.database = Objects.requireNonNull(database); this.crypto = Objects.requireNonNull(crypto);
    }
    private <T> T transaction(BiFunction<DSLContext, Instant, T> operation) {
        return database.transaction(sql -> {
            sql.fetchOne("select id from verification_control where id = 1 for update");
            Instant now = sql.fetchOne("select clock_timestamp() as now").get("now", OffsetDateTime.class).toInstant();
            return operation.apply(sql, now);
        });
    }
    @Override public Result enroll(EnrollmentCommand command) {
        return transaction((sql, now) -> {
            var b = command.binding();
            var existing = sql.fetchOne("select * from verification_enrollments where issuer = ? and subject = ? for update", b.issuer(), b.subject());
            String key = existing == null ? crypto.keyId() : existing.get("binding_key_id", String.class);
            byte[] requester = binding(key, b);
            if (existing != null && !crypto.matches(existing.get("binding_digest", byte[].class), requester)) return Result.rejected(Error.BINDING);
            return command(sql, now, requester, command.commandId(),
                new String[]{"enroll", b.issuer(), b.subject(), command.phone(), command.email()}, () -> {
                    if (existing != null) {
                        if (!now.isBefore(instant(existing, "expires_at")) || !command.phone().equals(existing.get("phone"))
                                || !command.email().equals(existing.get("email"))) return Result.rejected(Error.CONFLICT);
                        return enrollment(sql, existing);
                    }
                    // Never infer legacy ownership or verified state from matching email/phone.
                    if (exists(sql, "select 1 from external_identities where issuer = ? and subject = ?", b.issuer(), b.subject())
                            || exists(sql, "select 1 from identities where registration_phone = ?", command.phone())
                            || exists(sql, "select 1 from verification_phone_claims where phone = ?", command.phone()))
                        return Result.rejected(Error.CONFLICT);
                    if (count(sql, "select count(*) as n from verification_enrollments") >= 10_000
                            || count(sql, "select count(*) as n from verification_enrollments where created_at > cast(? as timestamptz)", at(now.minusSeconds(3600))) >= 1_000)
                        return Result.rejected(Error.RATE_LIMITED);
                    UUID id = UUID.randomUUID();
                    sql.execute("""
                        insert into verification_enrollments(id, issuer, subject, binding_digest, binding_key_id,
                            phone, email, state, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, 'PENDING_CONTACTS', cast(? as timestamptz), cast(? as timestamptz))
                        """, id, b.issuer(), b.subject(), requester, key, command.phone(), command.email(), at(now), at(now.plusSeconds(86400)));
                    sql.execute("insert into verification_phone_claims(phone, enrollment_id) values (?, ?)", command.phone(), id);
                    return new Result(id, null, null, 1, "PENDING_CONTACTS", now.plusSeconds(86400), null, 0, null);
                });
        });
    }
    @Override public Result status(UUID id, Binding binding) {
        return transaction((sql, now) -> {
            var row = bound(sql, id, binding);
            if (row == null) return Result.rejected(Error.BINDING);
            if (!now.isBefore(instant(row, "expires_at"))) return Result.rejected(Error.CONFLICT);
            return enrollment(sql, row);
        });
    }
    @Override public Result issue(ChallengeCommand input) {
        return transaction((sql, now) -> {
            var e = bound(sql, input.enrollmentId(), input.binding());
            if (e == null) return Result.rejected(Error.BINDING);
            return command(sql, now, e.get("binding_digest", byte[].class), input.commandId(),
                new String[]{"issue", input.enrollmentId().toString(), input.purpose().name(), Integer.toString(input.contactVersion())}, () -> {
                    if (!pending(e, input.contactVersion(), now)) return Result.rejected(Error.CONFLICT);
                    if (exists(sql, "select 1 from verification_receipts where enrollment_id = ? and purpose = ? and contact_version = ?",
                            input.enrollmentId(), input.purpose().name(), input.contactVersion())) return Result.rejected(Error.CONFLICT);
                    var recent = sql.fetchOne("""
                        select max(created_at) as last, count(*) filter (where created_at > cast(? as timestamptz)) as hourly,
                            count(*) filter (where created_at > cast(? as timestamptz)) as daily
                        from verification_challenges where enrollment_id = ? and purpose = ?
                        """, at(now.minusSeconds(3600)), at(now.minusSeconds(86400)), input.enrollmentId(), input.purpose().name());
                    OffsetDateTime last = recent.get("last", OffsetDateTime.class);
                    if ((last != null && now.isBefore(last.toInstant().plusSeconds(60)))
                            || recent.get("hourly", Long.class) >= 3 || recent.get("daily", Long.class) >= 10
                            || count(sql, "select count(*) as n from verification_challenges where created_at > cast(? as timestamptz)", at(now.minusSeconds(3600))) >= 1_000
                            || count(sql, "select count(*) as n from verification_challenges") >= 100_000)
                        return Result.rejected(Error.RATE_LIMITED);
                    sql.execute("""
                        update verification_challenges set state = 'SUPERSEDED', digest = null
                        where enrollment_id = ? and purpose = ? and state in ('PENDING_DELIVERY','OPEN')
                        """, input.enrollmentId(), input.purpose().name());
                    sql.execute("""
                        update verification_delivery_outbox set state = 'EXPIRED', ciphertext = null, nonce = null, fence = null
                        where enrollment_id = ? and purpose = ? and state in ('PENDING','LEASED','RETRY_WAIT')
                        """, input.enrollmentId(), input.purpose().name());
                    UUID challenge = UUID.randomUUID(), delivery = UUID.randomUUID();
                    String contact = e.get(input.purpose() == Purpose.REGISTRATION_PHONE ? "phone" : "email", String.class);
                    String code = crypto.newCode();
                    var encrypted = crypto.encrypt(input.enrollmentId(), challenge, input.purpose(), input.contactVersion(), contact, code);
                    byte[] digest = crypto.codeDigest(crypto.keyId(), input.enrollmentId(), e.get("issuer", String.class),
                        e.get("subject", String.class), challenge, input.purpose(), input.contactVersion(), contact, code);
                    Instant expiry = now.plusSeconds(300);
                    sql.execute("""
                        insert into verification_challenges(id, enrollment_id, purpose, contact_version, state, digest,
                            digest_key_id, created_at, expires_at, resend_at) values (?, ?, ?, ?, 'PENDING_DELIVERY', ?, ?, cast(? as timestamptz), cast(? as timestamptz), cast(? as timestamptz))
                        """, challenge, input.enrollmentId(), input.purpose().name(), input.contactVersion(), digest,
                        crypto.keyId(), at(now), at(expiry), at(now.plusSeconds(60)));
                    sql.execute("""
                        insert into verification_delivery_outbox(id, challenge_id, enrollment_id, purpose, contact_version,
                            state, key_id, nonce, ciphertext, available_at, expires_at)
                        values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz))
                        """, delivery, challenge, input.enrollmentId(), input.purpose().name(), input.contactVersion(),
                        encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext(), at(now), at(expiry));
                    return new Result(input.enrollmentId(), challenge, null, input.contactVersion(), "PENDING_DELIVERY", expiry, now.plusSeconds(60), 5, null);
                });
        });
    }
    @Override public Result consume(ConsumeCommand input) {
        return transaction((sql, now) -> {
            var e = bound(sql, input.enrollmentId(), input.binding());
            if (e == null) return Result.rejected(Error.BINDING);
            return command(sql, now, e.get("binding_digest", byte[].class), input.commandId(),
                new String[]{"consume", input.enrollmentId().toString(), input.challengeId().toString(), input.purpose().name(),
                    Integer.toString(input.contactVersion()), input.code()}, () -> {
                    if (!pending(e, input.contactVersion(), now)) return Result.rejected(Error.CONFLICT);
                    var c = sql.fetchOne("select * from verification_challenges where id = ? and enrollment_id = ? for update", input.challengeId(), input.enrollmentId());
                    if (c == null || !input.purpose().name().equals(c.get("purpose"))
                            || input.contactVersion() != c.get("contact_version", Integer.class)) return Result.rejected(Error.CONFLICT);
                    if (!now.isBefore(instant(c, "expires_at"))) {
                        sql.execute("update verification_challenges set state = 'EXPIRED', digest = null where id = ? and state in ('OPEN','PENDING_DELIVERY')", input.challengeId());
                        retire(sql, input.challengeId(), "EXPIRED");
                        return Result.rejected(Error.CONFLICT);
                    }
                    if (!"OPEN".equals(c.get("state"))) return Result.rejected(Error.CONFLICT);
                    String contact = e.get(input.purpose() == Purpose.REGISTRATION_PHONE ? "phone" : "email", String.class);
                    byte[] supplied = crypto.codeDigest(c.get("digest_key_id", String.class), input.enrollmentId(),
                        e.get("issuer", String.class), e.get("subject", String.class), input.challengeId(), input.purpose(), input.contactVersion(), contact, input.code());
                    if (!crypto.matches(c.get("digest", byte[].class), supplied)) {
                        int attempts = c.get("attempts", Integer.class) + 1;
                        sql.execute("update verification_challenges set attempts = ?, state = ?, digest = case when ? = 5 then null else digest end where id = ?",
                            attempts, attempts == 5 ? "LOCKED" : "OPEN", attempts, input.challengeId());
                        return Result.rejected(Error.CONFLICT);
                    }
                    UUID receipt = UUID.randomUUID();
                    sql.execute("insert into verification_receipts(id, enrollment_id, challenge_id, purpose, contact_version, created_at) values (?, ?, ?, ?, ?, cast(? as timestamptz))",
                        receipt, input.enrollmentId(), input.challengeId(), input.purpose().name(), input.contactVersion(), at(now));
                    sql.execute("update verification_challenges set state = 'CONSUMED', digest = null where id = ?", input.challengeId());
                    if (count(sql, "select count(*) as n from verification_receipts where enrollment_id = ? and contact_version = ?", input.enrollmentId(), input.contactVersion()) == 2)
                        sql.execute("update verification_enrollments set state = 'VERIFIED' where id = ?", input.enrollmentId());
                    // VERIFIED deliberately remains ineligible: no identity or fake projection ACK.
                    return new Result(input.enrollmentId(), input.challengeId(), receipt, input.contactVersion(), "CONSUMED", null, null, 5 - c.get("attempts", Integer.class), null);
                });
        });
    }
    private Record bound(DSLContext sql, UUID id, Binding binding) {
        var e = sql.fetchOne("select * from verification_enrollments where id = ? for update", id);
        if (e == null || !binding.issuer().equals(e.get("issuer")) || !binding.subject().equals(e.get("subject"))) return null;
        return crypto.matches(e.get("binding_digest", byte[].class), binding(e.get("binding_key_id", String.class), binding)) ? e : null;
    }
    private byte[] binding(String key, Binding binding) {
        return crypto.fingerprint(key, "binding-v1", binding.issuer(), binding.subject(), binding.session());
    }
    private static boolean pending(Record e, int version, Instant now) {
        return "PENDING_CONTACTS".equals(e.get("state")) && version == e.get("contact_version", Integer.class)
            && now.isBefore(instant(e, "expires_at"));
    }
    private static Result enrollment(DSLContext sql, Record e) {
        return new Result(e.get("id", UUID.class), null, null, e.get("contact_version", Integer.class), e.get("state", String.class), instant(e, "expires_at"), null, 0, null, receipt(sql, e, Purpose.REGISTRATION_EMAIL), receipt(sql, e, Purpose.REGISTRATION_PHONE));
    }
    private Result command(DSLContext sql, Instant now, byte[] requester, UUID command, String[] payload, Supplier<Result> action) {
        var existing = sql.fetchOne("select * from verification_commands where requester_digest = ? and command_id = ?", requester, command);
        if (existing != null) {
            if (!crypto.matches(existing.get("payload_hash", byte[].class), crypto.fingerprint(existing.get("hash_key_id", String.class), payload))
                    || !now.isBefore(instant(existing, "expires_at"))) return Result.rejected(Error.CONFLICT);
            return decode(existing.get("result", String.class));
        }
        if (count(sql, "select count(*) as n from verification_commands") >= 100_000) return Result.rejected(Error.RATE_LIMITED);
        Result result = action.get();
        sql.execute("insert into verification_commands(requester_digest, command_id, hash_key_id, payload_hash, result, created_at, expires_at) values (?, ?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz))",
            requester, command, crypto.keyId(), crypto.fingerprint(crypto.keyId(), payload), encode(result), at(now), at(now.plusSeconds(604800)));
        return result;
    }
    private static String encode(Result r) {
        return String.join("|", text(r.enrollmentId()), text(r.challengeId()), text(r.receiptId()), Integer.toString(r.contactVersion()),
            r.state(), text(r.expiresAt()), text(r.resendAt()), Integer.toString(r.attemptsRemaining()), text(r.error()), Boolean.toString(r.emailVerified()), Boolean.toString(r.phoneVerified()));
    }
    private static Result decode(String text) {
        String[] a = text.split("\\|", -1);
        return new Result(uuid(a[0]), uuid(a[1]), uuid(a[2]), Integer.parseInt(a[3]), a[4], time(a[5]), time(a[6]), Integer.parseInt(a[7]), a[8].isEmpty() ? null : Error.valueOf(a[8]), Boolean.parseBoolean(a[9]), Boolean.parseBoolean(a[10]));
    }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static UUID uuid(String value) { return value.isEmpty() ? null : UUID.fromString(value); }
    private static Instant time(String value) { return value.isEmpty() ? null : Instant.parse(value); }
    // Plain-SQL Object bindings may be sent as varchar by jOOQ/JDBC. Every at(...) placeholder
    // explicitly casts to timestamptz, preserving this UTC offset and database-time comparisons.
    private static OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(Record row, String field) { return row.get(field, OffsetDateTime.class).toInstant(); }
    private static boolean receipt(DSLContext sql, Record e, Purpose purpose) {
        return exists(sql, "select 1 from verification_receipts where enrollment_id = ? and contact_version = ? and purpose = ?",
            e.get("id", UUID.class), e.get("contact_version", Integer.class), purpose.name());
    }
    private static boolean exists(DSLContext sql, String query, Object... bindings) { return sql.fetchOne(query, bindings) != null; }
    private static long count(DSLContext sql, String query, Object... bindings) { return sql.fetchOne(query, bindings).get("n", Long.class); }
    private static void retire(DSLContext sql, UUID challenge, String state) {
        sql.execute("update verification_delivery_outbox set state = ?, nonce = null, ciphertext = null, fence = null where challenge_id = ? and state not in ('ACCEPTED','FAILED','EXPIRED')", state, challenge);
    }

    /** Internal worker lease; payload is ciphertext and is never included in toString. */
    public record DeliveryLease(UUID deliveryId, UUID fence, UUID enrollmentId, UUID challengeId, Purpose purpose,
                                int contactVersion, Instant expiresAt, VerificationCrypto.Encrypted encrypted) {
        @Override public String toString() { return "VerificationDeliveryLease[redacted]"; }
    }
    public Optional<DeliveryLease> claimDelivery() {
        return transaction((sql, now) -> {
            expirePayloads(sql, now);
            var d = sql.fetchOne("""
                select d.* from verification_delivery_outbox d join verification_challenges c on c.id = d.challenge_id
                where d.expires_at > cast(? as timestamptz) and c.state = 'PENDING_DELIVERY' and d.attempts < 3
                    and ((d.state in ('PENDING','RETRY_WAIT') and d.available_at <= cast(? as timestamptz)) or (d.state = 'LEASED' and d.lease_until <= cast(? as timestamptz)))
                order by d.available_at, d.id limit 1 for update of d
                """, at(now), at(now), at(now));
            if (d == null) return Optional.empty();
            UUID fence = UUID.randomUUID();
            sql.execute("update verification_delivery_outbox set state = 'LEASED', attempts = attempts + 1, fence = ?, lease_until = cast(? as timestamptz) where id = ?",
                fence, at(now.plusSeconds(30)), d.get("id", UUID.class));
            return Optional.of(new DeliveryLease(d.get("id", UUID.class), fence, d.get("enrollment_id", UUID.class),
                d.get("challenge_id", UUID.class), Purpose.valueOf(d.get("purpose", String.class)), d.get("contact_version", Integer.class),
                instant(d, "expires_at"), new VerificationCrypto.Encrypted(d.get("key_id", String.class), d.get("nonce", byte[].class), d.get("ciphertext", byte[].class))));
        });
    }
    /** Provider acknowledgement opens the challenge, never creates a receipt. */
    public boolean finishDelivery(UUID delivery, UUID fence, boolean accepted) {
        return transaction((sql, now) -> {
            var d = sql.fetchOne("select * from verification_delivery_outbox where id = ? for update", delivery);
            if (d == null || !"LEASED".equals(d.get("state")) || !fence.equals(d.get("fence"))
                    || !now.isBefore(instant(d, "lease_until"))) return false;
            UUID challenge = d.get("challenge_id", UUID.class);
            var c = sql.fetchOne("select state from verification_challenges where id = ?", challenge);
            if (!now.isBefore(instant(d, "expires_at")) || !"PENDING_DELIVERY".equals(c.get("state"))) {
                retire(sql, challenge, "EXPIRED"); return false;
            }
            if (accepted) {
                sql.execute("update verification_delivery_outbox set state = 'ACCEPTED', nonce = null, ciphertext = null, fence = null where id = ?", delivery);
                sql.execute("update verification_challenges set state = 'OPEN' where id = ?", challenge);
            } else if (d.get("attempts", Integer.class) >= 3) {
                retire(sql, challenge, "FAILED");
                sql.execute("update verification_challenges set state = 'DELIVERY_FAILED', digest = null where id = ?", challenge);
            } else {
                int delay = d.get("attempts", Integer.class) == 1 ? 5 : 15;
                sql.execute("update verification_delivery_outbox set state = 'RETRY_WAIT', available_at = cast(? as timestamptz), fence = null where id = ?", at(now.plusSeconds(delay)), delivery);
            }
            return true;
        });
    }
    /** Bounded tables fail closed at capacity; no automatic release of ownership claims. */
    public void expireDeliveryPayloads() { transaction((sql, now) -> { expirePayloads(sql, now); return null; }); }
    private static void expirePayloads(DSLContext sql, Instant now) {
        sql.execute("update verification_delivery_outbox set state = 'EXPIRED', nonce = null, ciphertext = null, fence = null where expires_at <= cast(? as timestamptz) and state not in ('ACCEPTED','FAILED','EXPIRED')", at(now));
        sql.execute("update verification_challenges set state = 'EXPIRED', digest = null where expires_at <= cast(? as timestamptz) and state in ('OPEN','PENDING_DELIVERY')", at(now));
        sql.execute("update verification_challenges set digest = null where expires_at < cast(? as timestamptz)", at(now.minusSeconds(86400)));
        // A worker that repeatedly dies after claiming its third lease must terminate safely.
        sql.execute("""
            update verification_challenges set state = 'DELIVERY_FAILED', digest = null where id in
                (select challenge_id from verification_delivery_outbox where state = 'LEASED' and attempts = 3 and lease_until <= cast(? as timestamptz))
            """, at(now));
        sql.execute("update verification_delivery_outbox set state = 'FAILED', nonce = null, ciphertext = null, fence = null where state = 'LEASED' and attempts = 3 and lease_until <= cast(? as timestamptz)", at(now));
    }
}
