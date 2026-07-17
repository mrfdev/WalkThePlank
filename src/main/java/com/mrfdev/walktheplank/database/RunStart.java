package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Idempotent run-start input; the repository captures the active season atomically. */
public record RunStart(
        UUID id,
        UUID playerId,
        String username,
        String arenaId,
        Instant startedAt,
        String release) {

    public RunStart {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        username = PersistenceValidation.playerName(username);
        arenaId = PersistenceValidation.text(arenaId, "arenaId", 128);
        startedAt = PersistenceValidation.instant(startedAt, "startedAt");
        release = PersistenceValidation.text(release, "release", 128);
    }
}
