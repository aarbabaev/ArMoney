package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.thread.Scheduler;

/** Absolute inbound-body deadline only, not a database/network operation deadline.
 * HTTP/1.1 exclusively: closing this endpoint must never abort HTTP/2 sibling streams. */
public final class LoginProofBodyDeadline implements AutoCloseable {
    private static final long LIMIT = TimeUnit.SECONDS.toNanos(4);
    private enum State { OPEN, COMPLETED, EXPIRED }
    private final long started;
    private final Scheduler scheduler;
    private final EndPoint endpoint;
    private State state = State.OPEN;
    private Scheduler.Task task;

    public static LoginProofBodyDeadline begin(HttpServletRequest request) {
        long started = System.nanoTime();
        if (!"HTTP/1.1".equals(request.getProtocol())) throw failure(LoginProof.Error.BAD_INPUT);
        try {
            var core = ServletContextRequest.getServletContextRequest(request);
            if (core == null) throw failure(LoginProof.Error.UNAVAILABLE);
            return new LoginProofBodyDeadline(core.getComponents().getScheduler(),
                core.getConnectionMetaData().getConnection().getEndPoint(), started);
        } catch (LoginProof.Failure denied) { throw denied; }
        catch (RuntimeException unavailable) { throw failure(LoginProof.Error.UNAVAILABLE); }
    }
    LoginProofBodyDeadline(Scheduler scheduler, EndPoint endpoint, long started) {
        if (scheduler == null || !scheduler.isRunning() || endpoint == null || !endpoint.isOpen())
            throw failure(LoginProof.Error.UNAVAILABLE);
        this.scheduler = scheduler; this.endpoint = endpoint; this.started = started;
    }
    public byte[] read(InputStream input, int limit) throws IOException {
        arm();
        try {
            byte[] bytes = input.readNBytes(limit);
            synchronized (this) {
                if (state != State.OPEN || remaining() <= 0) {
                    expire();
                    throw failure(LoginProof.Error.BAD_INPUT);
                }
                state = State.COMPLETED;
                cancel();
            }
            return bytes;
        } finally { close(); }
    }
    private synchronized void arm() {
        if (state != State.OPEN || task != null) throw failure(LoginProof.Error.UNAVAILABLE);
        if (remaining() <= 0) { expire(); throw failure(LoginProof.Error.BAD_INPUT); }
        if (!scheduler.isRunning()) throw failure(LoginProof.Error.UNAVAILABLE);
        try {
            long remaining = remaining();
            if (remaining <= 0) { expire(); throw failure(LoginProof.Error.BAD_INPUT); }
            task = scheduler.schedule(this::timeout, remaining, TimeUnit.NANOSECONDS);
            if (task == null) throw failure(LoginProof.Error.UNAVAILABLE);
            if (state != State.OPEN || remaining() <= 0) { expire(); throw failure(LoginProof.Error.BAD_INPUT); }
        } catch (LoginProof.Failure denied) {
            close();
            throw denied;
        } catch (RuntimeException unavailable) {
            close();
            throw failure(LoginProof.Error.UNAVAILABLE);
        }
    }
    private synchronized void timeout() {
        if (state == State.OPEN) expire();
    }
    /** Physical close and successful completion share this monitor: no late close of keepalive. */
    private void expire() {
        if (state != State.OPEN) return;
        state = State.EXPIRED;
        try { endpoint.close(new TimeoutException("login_proof_body_deadline")); }
        finally { cancel(); }
    }
    private long remaining() { return LIMIT - (System.nanoTime() - started); }
    private void cancel() { if (task != null) { Scheduler.Task pending = task; task = null; pending.cancel(); } }
    @Override public synchronized void close() {
        if (state == State.OPEN) state = State.COMPLETED;
        cancel();
    }
    private static LoginProof.Failure failure(LoginProof.Error error) { return new LoginProof.Failure(error); }
}
