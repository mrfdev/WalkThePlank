package com.mrfdev.walktheplank.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Main-thread FIFO queue with explicit, expiring readiness claims. */
public final class PlayerQueue {
    private final long joinCooldownMillis;
    private final long readinessWindowMillis;
    private final LinkedHashMap<UUID, Long> waitingSince = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Long> readyUntil = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new LinkedHashMap<>();

    private boolean paused;
    private long pausedAtMillis;

    public PlayerQueue(Duration joinCooldown, Duration readinessWindow) {
        Objects.requireNonNull(joinCooldown, "joinCooldown");
        Objects.requireNonNull(readinessWindow, "readinessWindow");
        if (joinCooldown.isNegative() || joinCooldown.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("joinCooldown must be between zero and ten minutes");
        }
        if (readinessWindow.isZero()
                || readinessWindow.isNegative()
                || readinessWindow.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("readinessWindow must be between one millisecond and five minutes");
        }
        joinCooldownMillis = joinCooldown.toMillis();
        readinessWindowMillis = readinessWindow.toMillis();
    }

    public JoinResult join(UUID playerId, Instant now) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        long timestamp = epochMillis(now);
        expireCooldowns(timestamp);
        if (readyUntil.containsKey(checkedId)) {
            return new JoinResult(JoinState.ALREADY_READY, positionOf(checkedId), Duration.ZERO);
        }
        if (waitingSince.containsKey(checkedId)) {
            return new JoinResult(JoinState.ALREADY_WAITING, positionOf(checkedId), Duration.ZERO);
        }
        long blockedUntil = cooldownUntil.getOrDefault(checkedId, 0L);
        if (blockedUntil > timestamp) {
            return new JoinResult(
                    JoinState.COOLDOWN,
                    0,
                    Duration.ofMillis(blockedUntil - timestamp));
        }
        waitingSince.put(checkedId, timestamp);
        return new JoinResult(JoinState.JOINED, positionOf(checkedId), Duration.ZERO);
    }

    public boolean leave(UUID playerId, Instant now) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        boolean removed = waitingSince.remove(checkedId) != null;
        removed |= readyUntil.remove(checkedId) != null;
        if (removed) {
            applyCooldown(checkedId, epochMillis(now));
        }
        return removed;
    }

    /** Removes an ineligible/quitting player and applies the reconnect-manipulation cooldown. */
    public boolean remove(UUID playerId, Instant now) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        boolean removed = waitingSince.remove(checkedId) != null;
        removed |= readyUntil.remove(checkedId) != null;
        if (removed) {
            applyCooldown(checkedId, epochMillis(now));
        }
        return removed;
    }

    /**
     * Assigns at most one expiring readiness claim per currently available arena.
     * Offline/ineligible players never retain a claim or a queue position.
     */
    public QueueUpdate refresh(
            int availableArenas,
            Predicate<UUID> eligible,
            Instant now) {
        if (availableArenas < 0) {
            throw new IllegalArgumentException("availableArenas must not be negative");
        }
        Objects.requireNonNull(eligible, "eligible");
        long timestamp = epochMillis(now);
        expireCooldowns(timestamp);

        List<UUID> expired = new ArrayList<>();
        if (!paused) {
            for (Map.Entry<UUID, Long> entry : List.copyOf(readyUntil.entrySet())) {
                UUID playerId = entry.getKey();
                if (entry.getValue() <= timestamp) {
                    readyUntil.remove(playerId);
                    applyCooldown(playerId, timestamp);
                    expired.add(playerId);
                }
            }
        }

        List<UUID> removed = new ArrayList<>();
        for (UUID playerId : List.copyOf(readyUntil.keySet())) {
            if (!eligible.test(playerId)) {
                readyUntil.remove(playerId);
                applyCooldown(playerId, timestamp);
                removed.add(playerId);
            }
        }
        for (UUID playerId : List.copyOf(waitingSince.keySet())) {
            if (!eligible.test(playerId)) {
                waitingSince.remove(playerId);
                applyCooldown(playerId, timestamp);
                removed.add(playerId);
            }
        }

        List<ReadyClaim> assigned = new ArrayList<>();
        if (!paused) {
            while (readyUntil.size() < availableArenas && !waitingSince.isEmpty()) {
                UUID playerId = waitingSince.keySet().iterator().next();
                waitingSince.remove(playerId);
                long expiry = saturatedAdd(timestamp, readinessWindowMillis);
                readyUntil.put(playerId, expiry);
                assigned.add(new ReadyClaim(playerId, Instant.ofEpochMilli(expiry)));
            }
        }
        return new QueueUpdate(assigned, expired, removed);
    }

    /** Consumes a live readiness claim immediately before attempting to start a run. */
    public boolean consumeReady(UUID playerId, Instant now) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        if (paused) {
            return false;
        }
        long timestamp = epochMillis(now);
        Long expiry = readyUntil.remove(checkedId);
        if (expiry == null) {
            return false;
        }
        if (expiry <= timestamp) {
            applyCooldown(checkedId, timestamp);
            return false;
        }
        return true;
    }

    public Optional<Instant> readyUntil(UUID playerId) {
        Long expiry = readyUntil.get(Objects.requireNonNull(playerId, "playerId"));
        if (expiry == null) {
            return Optional.empty();
        }
        long effectiveExpiry = paused
                ? saturatedAdd(expiry, Math.max(0L, System.currentTimeMillis() - pausedAtMillis))
                : expiry;
        return Optional.of(Instant.ofEpochMilli(effectiveExpiry));
    }

    public int position(UUID playerId) {
        return positionOf(Objects.requireNonNull(playerId, "playerId"));
    }

    public int waitingCount() {
        return waitingSince.size();
    }

    public int readyCount() {
        return readyUntil.size();
    }

    public int size() {
        return waitingSince.size() + readyUntil.size();
    }

    public List<UUID> orderedPlayers() {
        List<UUID> ordered = new ArrayList<>(size());
        ordered.addAll(readyUntil.keySet());
        ordered.addAll(waitingSince.keySet());
        return List.copyOf(ordered);
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        setPaused(paused, Instant.now());
    }

    public void setPaused(boolean paused, Instant now) {
        long timestamp = epochMillis(now);
        if (this.paused == paused) {
            return;
        }
        if (paused) {
            this.paused = true;
            pausedAtMillis = timestamp;
            return;
        }
        long pausedDuration = Math.max(0L, timestamp - pausedAtMillis);
        readyUntil.replaceAll((ignored, expiry) -> saturatedAdd(expiry, pausedDuration));
        this.paused = false;
        pausedAtMillis = 0L;
    }

    /** Clears all claims and waiting entries, returning the affected players in FIFO order. */
    public List<UUID> drain() {
        List<UUID> removed = orderedPlayers();
        readyUntil.clear();
        waitingSince.clear();
        return removed;
    }

    private int positionOf(UUID playerId) {
        int position = 1;
        for (UUID queuedId : readyUntil.keySet()) {
            if (queuedId.equals(playerId)) {
                return position;
            }
            position++;
        }
        for (UUID queuedId : waitingSince.keySet()) {
            if (queuedId.equals(playerId)) {
                return position;
            }
            position++;
        }
        return 0;
    }

    private void applyCooldown(UUID playerId, long timestamp) {
        if (joinCooldownMillis > 0L) {
            cooldownUntil.put(playerId, saturatedAdd(timestamp, joinCooldownMillis));
        }
    }

    private void expireCooldowns(long timestamp) {
        cooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= timestamp);
    }

    private static long epochMillis(Instant instant) {
        return Objects.requireNonNull(instant, "now").toEpochMilli();
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public enum JoinState {
        JOINED,
        ALREADY_WAITING,
        ALREADY_READY,
        COOLDOWN
    }

    public record JoinResult(JoinState state, int position, Duration cooldownRemaining) {
        public JoinResult {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(cooldownRemaining, "cooldownRemaining");
        }
    }

    public record ReadyClaim(UUID playerId, Instant expiresAt) {
        public ReadyClaim {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record QueueUpdate(
            List<ReadyClaim> assigned,
            List<UUID> expired,
            List<UUID> removed) {
        public QueueUpdate {
            assigned = List.copyOf(assigned);
            expired = List.copyOf(expired);
            removed = List.copyOf(removed);
        }
    }
}
