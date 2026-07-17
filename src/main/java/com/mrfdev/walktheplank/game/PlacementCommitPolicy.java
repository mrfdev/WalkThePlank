package com.mrfdev.walktheplank.game;

import java.util.Objects;
import java.util.UUID;

/** Pure ownership gate applied before a durable platform is allowed to mutate the world. */
final class PlacementCommitPolicy {
    private PlacementCommitPolicy() {
    }

    static Decision decide(CapturedOwner captured, LiveOwner live) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(live, "live");
        if (!live.acceptingCompletions()) {
            return Decision.RUNTIME_CLOSING;
        }
        if (!live.sessionCurrent()) {
            return Decision.SESSION_REPLACED;
        }
        if (!live.playerOnline()) {
            return Decision.PLAYER_OFFLINE;
        }
        if (!captured.playerId().equals(live.playerId())) {
            return Decision.PLAYER_CHANGED;
        }
        if (!captured.runId().equals(live.runId())) {
            return Decision.RUN_CHANGED;
        }
        if (!captured.arenaId().equals(live.arenaId())) {
            return Decision.ARENA_CHANGED;
        }
        if (captured.sessionGeneration() != live.sessionGeneration()) {
            return Decision.SESSION_GENERATION_CHANGED;
        }
        if (captured.platformGeneration() != live.platformGeneration()) {
            return Decision.PLATFORM_GENERATION_CHANGED;
        }
        if (!captured.block().equals(live.block())) {
            return Decision.BLOCK_CHANGED;
        }
        if (!live.arenaLeaseOwned()) {
            return Decision.ARENA_LEASE_LOST;
        }
        if (!live.blockLeaseOwned()) {
            return Decision.BLOCK_LEASE_LOST;
        }
        return Decision.COMMIT;
    }

    record CapturedOwner(
            UUID playerId,
            UUID runId,
            String arenaId,
            long sessionGeneration,
            long platformGeneration,
            BlockKey block) {
        CapturedOwner {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(runId, "runId");
            arenaId = requireArenaId(arenaId);
            requirePositive(sessionGeneration, "sessionGeneration");
            requirePositive(platformGeneration, "platformGeneration");
            Objects.requireNonNull(block, "block");
        }
    }

    record LiveOwner(
            UUID playerId,
            UUID runId,
            String arenaId,
            long sessionGeneration,
            long platformGeneration,
            BlockKey block,
            boolean acceptingCompletions,
            boolean sessionCurrent,
            boolean playerOnline,
            boolean arenaLeaseOwned,
            boolean blockLeaseOwned) {
        LiveOwner {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(runId, "runId");
            arenaId = requireArenaId(arenaId);
            requirePositive(sessionGeneration, "sessionGeneration");
            requirePositive(platformGeneration, "platformGeneration");
            Objects.requireNonNull(block, "block");
        }
    }

    enum Decision {
        COMMIT,
        RUNTIME_CLOSING,
        SESSION_REPLACED,
        PLAYER_OFFLINE,
        PLAYER_CHANGED,
        RUN_CHANGED,
        ARENA_CHANGED,
        SESSION_GENERATION_CHANGED,
        PLATFORM_GENERATION_CHANGED,
        BLOCK_CHANGED,
        ARENA_LEASE_LOST,
        BLOCK_LEASE_LOST
    }

    private static String requireArenaId(String value) {
        Objects.requireNonNull(value, "arenaId");
        String checked = value.strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("arenaId must not be blank");
        }
        return checked;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
