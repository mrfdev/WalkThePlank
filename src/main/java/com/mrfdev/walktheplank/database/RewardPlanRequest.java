package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotent request to create a durable reward plan before any command is dispatched. */
public record RewardPlanRequest(
        UUID planId,
        UUID runId,
        String idempotencyKey,
        Instant createdAt,
        List<RewardStepSpec> steps) {

    public static final int MAX_STEPS = 100;

    public RewardPlanRequest {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(runId, "runId");
        idempotencyKey = PersistenceValidation.text(idempotencyKey, "idempotencyKey", 128);
        createdAt = PersistenceValidation.instant(createdAt, "createdAt");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "A reward plan must not exceed " + MAX_STEPS + " steps");
        }
    }
}
