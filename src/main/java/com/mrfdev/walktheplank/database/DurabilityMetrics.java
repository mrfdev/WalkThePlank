package com.mrfdev.walktheplank.database;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable operational counts for retained runs, seasons, and reward-ledger states. */
public record DurabilityMetrics(
        Instant capturedAt,
        long retainedRuns,
        int seasonCount,
        Optional<Season> activeSeason,
        Map<RewardPlanStatus, Long> rewardPlansByStatus,
        Map<RewardStepStatus, Long> rewardStepsByStatus,
        int pendingMutations,
        Optional<Instant> oldestPendingSince,
        Optional<Instant> lastSuccessfulWriteAt,
        Optional<SanitizedDatabaseFailure> lastDatabaseFailure) {

    public DurabilityMetrics {
        capturedAt = PersistenceValidation.instant(capturedAt, "capturedAt");
        if (retainedRuns < 0L || seasonCount < 0) {
            throw new IllegalArgumentException("Metric counts must not be negative");
        }
        if (pendingMutations < 0) {
            throw new IllegalArgumentException("pendingMutations must not be negative");
        }
        activeSeason = Objects.requireNonNull(activeSeason, "activeSeason");
        rewardPlansByStatus = Map.copyOf(
                Objects.requireNonNull(rewardPlansByStatus, "rewardPlansByStatus"));
        rewardStepsByStatus = Map.copyOf(
                Objects.requireNonNull(rewardStepsByStatus, "rewardStepsByStatus"));
        oldestPendingSince = PersistenceValidation.optionalInstant(
                oldestPendingSince, "oldestPendingSince");
        lastSuccessfulWriteAt = PersistenceValidation.optionalInstant(
                lastSuccessfulWriteAt, "lastSuccessfulWriteAt");
        lastDatabaseFailure = Objects.requireNonNull(lastDatabaseFailure, "lastDatabaseFailure");
    }

    public static DurabilityMetrics empty() {
        return new DurabilityMetrics(
                Instant.EPOCH,
                0L,
                0,
                Optional.empty(),
                Map.of(),
                Map.of(),
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public Optional<Duration> oldestPendingAge(Instant now) {
        Instant checkedNow = PersistenceValidation.instant(now, "now");
        return oldestPendingSince.map(start -> {
            if (checkedNow.isBefore(start)) {
                return Duration.ZERO;
            }
            return Duration.between(start, checkedNow);
        });
    }
}
