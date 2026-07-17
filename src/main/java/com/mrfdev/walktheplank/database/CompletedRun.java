package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Idempotent input for one completed parkour run. The run ID is its durable idempotency key. */
public record CompletedRun(
        UUID id,
        UUID playerId,
        String username,
        String arenaId,
        Instant startedAt,
        Instant endedAt,
        int score,
        String endReason,
        String release,
        Optional<UUID> seasonId) {

    public CompletedRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        username = PersistenceValidation.playerName(username);
        arenaId = PersistenceValidation.text(arenaId, "arenaId", 128);
        startedAt = PersistenceValidation.instant(startedAt, "startedAt");
        endedAt = PersistenceValidation.instant(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative");
        }
        endReason = PersistenceValidation.text(endReason, "endReason", 64);
        release = PersistenceValidation.text(release, "release", 128);
        seasonId = Objects.requireNonNull(seasonId, "seasonId");
    }

    public RunStart start() {
        return new RunStart(id, playerId, username, arenaId, startedAt, release);
    }

    public RunCompletion completion() {
        return new RunCompletion(id, endedAt, score, endReason);
    }
}
