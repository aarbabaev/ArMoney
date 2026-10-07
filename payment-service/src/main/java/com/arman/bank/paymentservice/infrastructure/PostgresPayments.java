package com.arman.bank.paymentservice.infrastructure;

import com.arman.bank.paymentservice.application.*;
import com.arman.bank.paymentservice.domain.*;
import com.arman.bank.runtime.Database;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;

public final class PostgresPayments implements PaymentStore {
    private final Database db;
    public PostgresPayments(Database db) { this.db = db; }

    @Override public Optional<Payment> findKey(UUID requester, String key, PaymentRequest request) {
        return db.transaction(sql -> {
            var row = sql.fetchOne("select * from payments where requester_id = ? and idempotency_key = ?", requester, key);
            return row == null ? Optional.empty() : Optional.of(replay(row, request));
        });
    }
    @Override public Payment create(UUID requester, String key, PaymentRequest request, PaymentPeers.Mapping mapping) {
        if (mapping == null || mapping.destinationWalletId() == null || mapping.debitAccountId() == null || mapping.creditAccountId() == null
                || request.sourceWalletId().equals(mapping.destinationWalletId()) || mapping.debitAccountId().equals(mapping.creditAccountId()))
            throw new PaymentFailure(503, "invalid_mapping");
        return db.transaction(sql -> {
            sql.execute("""
                insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,
                    currency,amount_minor,status,recipient_id,recipient_phone,debit_account_id,credit_account_id)
                values (?,?,?,?,?,?,?,?,'PENDING',?,?,?,?) on conflict(requester_id,idempotency_key) do nothing
                """, UUID.randomUUID(), requester, key, request.hash(), request.sourceWalletId(), mapping.destinationWalletId(),
                    request.currency(), request.amountMinor(), request.recipientId(), request.recipientPhone(), mapping.debitAccountId(), mapping.creditAccountId());
            return replay(sql.fetchOne("select * from payments where requester_id = ? and idempotency_key = ?", requester, key), request);
        });
    }
    private static Payment replay(org.jooq.Record row, PaymentRequest request) {
        if (!request.hash().equals(row.get("request_hash", String.class))) throw new PaymentFailure(409, "idempotency_conflict");
        if (row.get("recipient_id") == null) throw new PaymentFailure(409, "legacy_payment_unavailable");
        var payment = payment(row);
        if (!payment.request().equals(request)) throw new PaymentFailure(409, "idempotency_conflict");
        return payment;
    }
    @Override public Optional<Payment> visible(UUID owner, UUID id) {
        return db.transaction(sql -> Optional.ofNullable(sql.fetchOne("""
            select * from payments where id = ? and recipient_id is not null
              and (requester_id = ? or (recipient_id = ? and status = 'COMPLETED'))
            """, id, owner, owner)).map(PostgresPayments::payment));
    }
    @Override public List<Payment> history(UUID owner) {
        return db.transaction(sql -> sql.fetch("""
            select * from payments where recipient_id is not null
              and (requester_id = ? or (recipient_id = ? and status = 'COMPLETED'))
            order by created_at desc, id desc limit 100
            """, owner, owner).map(PostgresPayments::payment));
    }
    @Override public List<Notification> notifications(UUID owner) {
        return db.transaction(sql -> sql.fetch("select * from notifications where owner_id = ? order by created_at desc, id desc limit 100", owner)
                .map(PostgresPayments::notification));
    }
    @Override public Optional<Notification> readNotification(UUID owner, UUID id) {
        return db.transaction(sql -> Optional.ofNullable(sql.fetchOne("""
            update notifications set read_at = coalesce(read_at, clock_timestamp()) where id = ? and owner_id = ? returning *
            """, id, owner)).map(PostgresPayments::notification));
    }
    @Override public Optional<Claim> claim() {
        return db.transaction(sql -> {
            var row = sql.fetchOne("""
                select * from payments where status = 'PENDING' and recipient_id is not null
                  and next_attempt_at <= clock_timestamp() and (lease_until is null or lease_until <= clock_timestamp())
                order by next_attempt_at, created_at, id for update skip locked limit 1
                """);
            if (row == null) return Optional.empty();
            UUID token = UUID.randomUUID();
            var claimed = sql.fetchOne("""
                update payments set lease_token = ?, lease_until = clock_timestamp() + interval '30 seconds',
                  attempts = least(attempts, 999999) + 1 where id = ? returning *
                """, token, row.get("id", UUID.class));
            return Optional.of(new Claim(payment(claimed), token, claimed.get("attempts", Integer.class)));
        });
    }
    @Override public boolean finish(Claim claim, PaymentPeers.Outcome outcome) {
        Objects.requireNonNull(outcome);
        return db.transaction(sql -> {
            String status = outcome == PaymentPeers.Outcome.POSTED ? "COMPLETED" : "REJECTED";
            String reason = outcome == PaymentPeers.Outcome.POSTED ? null : outcome.name();
            var row = sql.fetchOne("""
                update payments set status = ?, rejection_reason = ?, updated_at = clock_timestamp(), lease_token = null, lease_until = null
                where id = ? and status = 'PENDING' and lease_token = ? and lease_until > clock_timestamp() returning *
                """, status, reason, claim.payment().id(), claim.token());
            if (row == null) return false;
            var p = payment(row);
            notify(sql, p.requesterId(), p, status.equals("COMPLETED") ? "PAYMENT_COMPLETED" : "PAYMENT_REJECTED");
            if (status.equals("COMPLETED")) notify(sql, p.request().recipientId(), p, "PAYMENT_RECEIVED");
            return true;
        });
    }
    private static void notify(DSLContext sql, UUID owner, Payment p, String type) {
        sql.execute("""
            insert into notifications(id,owner_id,payment_id,type,currency,amount_minor) values (?,?,?,?,?,?)
            on conflict(owner_id,payment_id,type) do nothing
            """, UUID.randomUUID(), owner, p.id(), type, p.request().currency(), p.request().amountMinor());
        sql.execute("""
            insert into email_outbox(id,owner_id,payment_id,type,amount_minor) values (?,?,?,?,?)
            on conflict(owner_id,payment_id,type) do nothing
            """, UUID.randomUUID(), owner, p.id(), type, p.request().amountMinor());
    }
    @Override public void retry(Claim claim) {
        int seconds = Math.min(60, 1 << Math.min(6, claim.attempts()));
        db.transaction(sql -> sql.execute("""
            update payments set lease_token = null, lease_until = null,
              next_attempt_at = clock_timestamp() + (? * interval '1 second')
            where id = ? and status = 'PENDING' and lease_token = ? and lease_until > clock_timestamp()
            """, seconds, claim.payment().id(), claim.token()));
    }
    private static Instant time(org.jooq.Record r, String field) {
        var value = r.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static Payment payment(org.jooq.Record r) {
        var request = new PaymentRequest(r.get("source_wallet_id", UUID.class), r.get("recipient_id", UUID.class),
                r.get("recipient_phone", String.class), r.get("currency", String.class), r.get("amount_minor", Long.class));
        return new Payment(r.get("id", UUID.class), r.get("requester_id", UUID.class), request, r.get("destination_wallet_id", UUID.class),
                r.get("debit_account_id", UUID.class), r.get("credit_account_id", UUID.class), r.get("request_hash", String.class),
                r.get("status", String.class), r.get("rejection_reason", String.class), time(r, "created_at"), time(r, "updated_at"));
    }
    private static Notification notification(org.jooq.Record r) {
        return new Notification(r.get("id", UUID.class), r.get("payment_id", UUID.class), r.get("type", String.class),
                r.get("currency", String.class), r.get("amount_minor", Long.class), time(r, "created_at"), time(r, "read_at"));
    }
}
