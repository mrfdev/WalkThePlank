package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Idempotent terminal score/reason for a previously persisted run start. */
public record RunCompletion(UUID runId, Instant endedAt, int score, String endReason) {
    public RunCompletion {
        Objects.requireNonNull(runId, "runId");
        endedAt = PersistenceValidation.instant(endedAt, "endedAt");
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative");
        }
        endReason = PersistenceValidation.text(endReason, "endReason", 64);
    }
}
