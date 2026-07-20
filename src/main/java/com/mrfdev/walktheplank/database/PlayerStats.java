package com.mrfdev.walktheplank.database;

import java.util.Objects;
import java.util.UUID;

/** Immutable player-facing statistics from a score snapshot. */
public record PlayerStats(
        long id,
        UUID uuid,
        String username,
        int bestScore,
        int rank,
        int totalEntries) {

    public PlayerStats {
        if (id < 0L) {
            throw new IllegalArgumentException("id must not be negative");
        }
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (totalEntries < 1) {
            throw new IllegalArgumentException("totalEntries must be positive");
        }
    }

    public int percentile() {
        return Math.max(1, (int) Math.ceil(rank * 100.0 / totalEntries));
    }
}
