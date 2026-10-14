package com.arman.bank.appgateway;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded per-authenticated-owner model admission quota; no client identity input. */
final class AssistantRateLimit {
    private final Clock clock;
    private final Map<UUID, Window> owners = new HashMap<>();
    AssistantRateLimit(Clock clock) { this.clock = clock; }
    synchronized boolean allow(UUID owner) {
        long minute = clock.instant().getEpochSecond() / 60;
        owners.values().removeIf(window -> window.minute != minute);
        Window window = owners.get(owner);
        if (window == null) {
            if (owners.size() >= 4096) return false;
            window = new Window(minute); owners.put(owner, window);
        }
        if (window.count >= 10) return false;
        window.count++; return true;
    }
    synchronized void clear() { owners.clear(); }
    private static final class Window {
        final long minute; int count;
        Window(long minute) { this.minute = minute; }
    }
}
