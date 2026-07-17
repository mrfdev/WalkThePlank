package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One ranked scoreboard row. An empty UUID represents an unresolved legacy row. */
public record ScoreEntry(
        long id,
        Optional<UUID> uuid,
        String username,
        int score,
        int rank,
        Optional<Instant> updatedAt) {

    public ScoreEntry {
        if (id < 0L) {
            throw new IllegalArgumentException("id must not be negative");
        }
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
