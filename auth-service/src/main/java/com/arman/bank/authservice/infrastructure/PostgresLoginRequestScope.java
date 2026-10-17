package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.runtime.Database;
import java.util.Objects;
import java.util.function.Function;
import org.jooq.DSLContext;

/** One authenticated operation, one exclusive PRIMARY connection and one global permit.
 * The callback may perform bounded remote work between transactions, never inside them. */
final class PostgresLoginRequestScope {
    private final Database database;
    private final PostgresLoginOperationGate gate = new PostgresLoginOperationGate();
    PostgresLoginRequestScope(Database database) { this.database = Objects.requireNonNull(database); }
    <T> T inRequest(String actor, Function<Request,T> action) {
        return database.withSession(session -> gate.withPermit(session, permit -> {
            Request request = new Request(actor, permit);
            try {
                boolean admitted = request.transaction(sql -> PostgresLoginAdmission.admit(sql, actor,
                    sql.fetchOne("select clock_timestamp()").get(0, java.time.OffsetDateTime.class).toInstant()));
                if (!admitted) throw new LoginProof.Failure(LoginProof.Error.RATE_LIMITED);
                T result = action.apply(request);
                request.check();
                return result;
            } finally { request.closed = true; }
        }));
    }
    static final class Request {
        private final String actor;
        private final PostgresLoginOperationGate.Permit permit;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private Request(String actor, PostgresLoginOperationGate.Permit permit) { this.actor = actor; this.permit = permit; }
        void check() {
            if (closed || Thread.currentThread() != owner || Thread.currentThread().isInterrupted())
                throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);
        }
        /** Post-COMMIT verification on this same exclusive backend before external work. */
        void verify() { check(); permit.verify(); check(); }
        String actor() { check(); return actor; }
        <T> T transaction(Function<DSLContext,T> action) {
            check();
            return permit.transaction(sql -> {
                try { PostgresVerificationProjectionStore.lockControls(sql); }
                catch (com.arman.bank.authservice.domain.VerificationProjection.Failure unavailable) {
                    throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);
                }
                T result = action.apply(sql);
                check();
                return result;
            });
        }
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
}
