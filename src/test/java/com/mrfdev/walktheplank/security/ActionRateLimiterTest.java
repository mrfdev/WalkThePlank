package com.mrfdev.walktheplank.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionRateLimiterTest {
    @Test
    void limitsActionsIndependentlyAndExpiresExactlyAtDeadline() {
        ActionRateLimiter limiter = new ActionRateLimiter();
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-14T12:00:00Z");

        assertTrue(limiter.tryAcquire(playerId, "menu.open", Duration.ofSeconds(2), now).allowed());
        ActionRateLimiter.Result denied = limiter.tryAcquire(
                playerId, "menu.open", Duration.ofSeconds(2), now.plusSeconds(1));
        assertFalse(denied.allowed());
        assertEquals(Duration.ofSeconds(1), denied.retryAfter());
        assertTrue(limiter.tryAcquire(
                playerId, "stats", Duration.ofSeconds(2), now.plusSeconds(1)).allowed());
        assertTrue(limiter.tryAcquire(
                playerId, "menu.open", Duration.ofSeconds(2), now.plusSeconds(2)).allowed());
    }

    @Test
    void clearingOnePlayerDoesNotAffectAnother() {
        ActionRateLimiter limiter = new ActionRateLimiter();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-14T12:00:00Z");
        limiter.tryAcquire(first, "play", Duration.ofSeconds(5), now);
        limiter.tryAcquire(second, "play", Duration.ofSeconds(5), now);

        limiter.clear(first);

        assertTrue(limiter.tryAcquire(first, "play", Duration.ofSeconds(5), now).allowed());
        assertFalse(limiter.tryAcquire(second, "play", Duration.ofSeconds(5), now).allowed());
    }
}
