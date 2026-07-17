package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An immutable, internally consistent view of the whole scoreboard.
 */
public final class ScoreSnapshot {
    private static final int DEFAULT_TOP_SIZE = 10;

    private final Instant capturedAt;
    private final List<ScoreEntry> scores;
    private final List<ScoreEntry> top;
    private final Map<UUID, PlayerStats> stats;

    ScoreSnapshot(Instant capturedAt, List<ScoreEntry> scores, Map<UUID, PlayerStats> stats) {
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.scores = List.copyOf(Objects.requireNonNull(scores, "scores"));
        this.top = this.scores.subList(0, Math.min(DEFAULT_TOP_SIZE, this.scores.size()));
        this.stats = Map.copyOf(Objects.requireNonNull(stats, "stats"));
    }

    static ScoreSnapshot empty() {
        return new ScoreSnapshot(Instant.EPOCH, List.of(), Map.of());
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    /** All scores, ordered by score descending and then by the original database ID. */
    public List<ScoreEntry> scores() {
        return scores;
    }

    /** Up to ten scoreboard rows. */
    public List<ScoreEntry> top() {
        return top;
    }

    /** Up to {@code limit} scoreboard rows from this same immutable snapshot. */
    public List<ScoreEntry> top(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        return scores.subList(0, Math.min(limit, scores.size()));
    }

    /** Looks up a resolved identity without consulting the database. */
    public Optional<PlayerStats> stats(UUID uuid) {
        return Optional.ofNullable(stats.get(Objects.requireNonNull(uuid, "uuid")));
    }

    public int totalEntries() {
        return scores.size();
    }
}
