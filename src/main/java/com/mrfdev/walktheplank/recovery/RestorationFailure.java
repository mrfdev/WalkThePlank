package com.mrfdev.walktheplank.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RestorationFailure(
        UUID journalId,
        String arenaId,
        String message,
        Instant occurredAt) {
    public RestorationFailure {
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(arenaId, "arenaId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
