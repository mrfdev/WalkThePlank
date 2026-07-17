package com.mrfdev.walktheplank.security;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded main-thread cooldown registry for non-critical player actions. */
public final class ActionRateLimiter {
    private static final int MAXIMUM_ENTRIES = 8_192;
    private static final Pattern ACTION_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,47}");

    private final Map<Key, Long> blockedUntil = new HashMap<>();

    public Result tryAcquire(UUID playerId, String action, Duration cooldown, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isNegative() || cooldown.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("cooldown must be between zero and ten minutes");
        }
        String checkedAction = validateAction(action);
        long timestamp = Objects.requireNonNull(now, "now").toEpochMilli();
        if (blockedUntil.size() >= MAXIMUM_ENTRIES) {
            blockedUntil.entrySet().removeIf(entry -> entry.getValue() <= timestamp);
            if (blockedUntil.size() >= MAXIMUM_ENTRIES) {
                return new Result(false, cooldown);
            }
        }

        Key key = new Key(playerId, checkedAction);
        long deadline = blockedUntil.getOrDefault(key, Long.MIN_VALUE);
        if (deadline > timestamp) {
            return new Result(false, Duration.ofMillis(deadline - timestamp));
        }
        if (cooldown.isZero()) {
            blockedUntil.remove(key);
        } else {
            blockedUntil.put(key, saturatedAdd(timestamp, cooldown.toMillis()));
        }
        return new Result(true, Duration.ZERO);
    }

    public void clear(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        blockedUntil.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public int trackedActions() {
        return blockedUntil.size();
    }

    private static String validateAction(String action) {
        Objects.requireNonNull(action, "action");
        String normalized = action.strip().toLowerCase(java.util.Locale.ROOT);
        if (!ACTION_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("action must be a short lowercase identifier");
        }
        return normalized;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record Key(UUID playerId, String action) {
    }

    public record Result(boolean allowed, Duration retryAfter) {
        public Result {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative() || allowed && !retryAfter.isZero()) {
                throw new IllegalArgumentException("invalid rate-limit result");
            }
        }
    }
}
