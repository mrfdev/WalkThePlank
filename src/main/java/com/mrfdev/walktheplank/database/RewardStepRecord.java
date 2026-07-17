package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable reward-step metadata; raw command arguments are deliberately absent. */
public record RewardStepRecord(
        int index,
        String commandRoot,
        String commandHash,
        RewardStepStatus status,
        Optional<Instant> attemptedAt,
        Optional<Instant> completedAt) {

    public RewardStepRecord {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        commandRoot = PersistenceValidation.commandRoot(commandRoot);
        commandHash = PersistenceValidation.commandHash(commandHash);
        Objects.requireNonNull(status, "status");
        attemptedAt = PersistenceValidation.optionalInstant(attemptedAt, "attemptedAt");
        completedAt = PersistenceValidation.optionalInstant(completedAt, "completedAt");
    }
}
