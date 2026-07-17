package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable reward plan and its non-sensitive command-step ledger. */
public record RewardPlanRecord(
        UUID planId,
        UUID runId,
        String idempotencyKey,
        RewardPlanStatus status,
        Instant createdAt,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        List<RewardStepRecord> steps) {

    public RewardPlanRecord {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(runId, "runId");
        idempotencyKey = PersistenceValidation.text(idempotencyKey, "idempotencyKey", 128);
        Objects.requireNonNull(status, "status");
        createdAt = PersistenceValidation.instant(createdAt, "createdAt");
        startedAt = PersistenceValidation.optionalInstant(startedAt, "startedAt");
        completedAt = PersistenceValidation.optionalInstant(completedAt, "completedAt");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }
}
