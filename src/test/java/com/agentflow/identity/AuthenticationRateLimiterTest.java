package com.agentflow.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthenticationRateLimiterTest {
    private final AuthenticationRateLimiter limiter = new AuthenticationRateLimiter();

    @Test void limitsRequestsPerIdentityAndResetsWindow() {
        long start = 1_000_000_000L;
        assertTrue(limiter.allow("login:127.0.0.1", 2, start));
        assertTrue(limiter.allow("login:127.0.0.1", 2, start + 1));
        assertFalse(limiter.allow("login:127.0.0.1", 2, start + 2));
        assertTrue(limiter.allow("login:127.0.0.1", 2, start + Duration.ofMinutes(1).toNanos()));
    }

    @Test void identitiesHaveIndependentBudgets() {
        long now = 5_000_000_000L;
        assertTrue(limiter.allow("login:127.0.0.1", 1, now));
        assertTrue(limiter.allow("register:127.0.0.1", 1, now));
        assertTrue(limiter.allow("login:127.0.0.2", 1, now));
    }
}
