package com.arman.bank.appgateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Turn-local read capabilities. Bearers never leave the gateway or survive a turn. */
final class AssistantDelegations {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Turn> active = new ConcurrentHashMap<>();

    Turn create(String bearer, UUID owner, long deadline) {
        byte[] bytes = new byte[32];
        Turn turn;
        do {
            random.nextBytes(bytes);
            turn = new Turn(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), bearer, owner, deadline);
        } while (active.putIfAbsent(turn.handle, turn) != null);
        return turn;
    }
    Turn acquire(String handle) {
        Turn turn = active.get(handle);
        if (turn == null || !turn.open || System.nanoTime() >= turn.deadline || turn.calls.incrementAndGet() > 8)
            return null;
        return turn;
    }
    void remove(Turn turn) { turn.open = false; active.remove(turn.handle, turn); }
    int size() { return active.size(); }
    void clear() { active.values().forEach(turn -> turn.open = false); active.clear(); }

    static final class Turn {
        final String handle, bearer;
        final UUID owner;
        final long deadline;
        final AtomicInteger calls = new AtomicInteger();
        final Map<String, JsonNode> balances = new ConcurrentHashMap<>();
        volatile JsonNode latestPayment;
        volatile boolean historyRead;
        volatile boolean open = true;
        Turn(String handle, String bearer, UUID owner, long deadline) {
            this.handle = handle; this.bearer = bearer; this.owner = owner; this.deadline = deadline;
        }
    }
}
