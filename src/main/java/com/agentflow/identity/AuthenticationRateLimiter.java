package com.agentflow.identity;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Small per-process IP limiter for public credential endpoints; never trusts forwarded headers. */
@Component
final class AuthenticationRateLimiter {
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private static final int MAX_IDENTITIES = 10_000;
    private final Map<String, Window> windows = new HashMap<>();
    private int requestsSinceCleanup;

    synchronized boolean allow(String identity, int limit, long nowNanos) {
        if (++requestsSinceCleanup >= 256 || windows.size() >= MAX_IDENTITIES) {
            windows.entrySet().removeIf(entry -> nowNanos - entry.getValue().startedAt >= WINDOW_NANOS);
            requestsSinceCleanup = 0;
        }
        Window window = windows.get(identity);
        if (window == null || nowNanos - window.startedAt >= WINDOW_NANOS) {
            if (window == null && windows.size() >= MAX_IDENTITIES) return false;
            windows.put(identity, new Window(nowNanos, 1));
            return true;
        }
        if (window.count >= limit) return false;
        window.count++;
        return true;
    }

    private static final class Window {
        final long startedAt;
        int count;
        Window(long startedAt, int count) { this.startedAt = startedAt; this.count = count; }
    }
}
