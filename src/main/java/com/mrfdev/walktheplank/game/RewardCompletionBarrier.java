package com.mrfdev.walktheplank.game;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local barrier preventing a new run while the previous run's rewards can still mutate it. */
final class RewardCompletionBarrier {
    private final ConcurrentMap<UUID, UUID> planByPlayer = new ConcurrentHashMap<>();

    boolean begin(UUID playerId, UUID planId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(planId, "planId");
        UUID existing = planByPlayer.putIfAbsent(playerId, planId);
        return existing == null || existing.equals(planId);
    }

    boolean isBlocked(UUID playerId) {
        return planByPlayer.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    boolean clear(UUID playerId, UUID planId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(planId, "planId");
        return planByPlayer.remove(playerId, planId);
    }
}
