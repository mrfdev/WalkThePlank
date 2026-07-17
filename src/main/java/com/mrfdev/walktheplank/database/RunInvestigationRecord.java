package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Redacted retained-run evidence; mutable player-name metadata is deliberately omitted. */
public record RunInvestigationRecord(
        long sequence,
        UUID runId,
        UUID playerId,
        String arenaId,
        Instant startedAt,
        Optional<Instant> endedAt,
        Optional<Integer> score,
        Optional<String> endReason,
        String release,
        Optional<UUID> seasonId,
        RunStatus status,
        Optional<UUID> rewardPlanId,
        Optional<RewardPlanStatus> rewardPlanStatus) {
    public RunInvestigationRecord {
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(playerId, "playerId");
        arenaId = PersistenceValidation.text(arenaId, "arenaId", 128);
        startedAt = PersistenceValidation.instant(startedAt, "startedAt");
        endedAt = PersistenceValidation.optionalInstant(endedAt, "endedAt");
        score = Objects.requireNonNull(score, "score");
        score.ifPresent(value -> {
            if (value < 0) {
                throw new IllegalArgumentException("score must not be negative");
            }
        });
        endReason = Objects.requireNonNull(endReason, "endReason")
                .map(value -> PersistenceValidation.text(value, "endReason", 64));
        release = PersistenceValidation.text(release, "release", 128);
        seasonId = Objects.requireNonNull(seasonId, "seasonId");
        Objects.requireNonNull(status, "status");
        rewardPlanId = Objects.requireNonNull(rewardPlanId, "rewardPlanId");
        rewardPlanStatus = Objects.requireNonNull(rewardPlanStatus, "rewardPlanStatus");
        if (rewardPlanId.isPresent() != rewardPlanStatus.isPresent()) {
            throw new IllegalArgumentException(
                    "reward plan ID and status must either both be present or both be absent");
        }
        if (endedAt.isPresent() && endedAt.orElseThrow().isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (status == RunStatus.STARTED
                && (endedAt.isPresent() || score.isPresent() || endReason.isPresent())) {
            throw new IllegalArgumentException("A started run cannot have terminal fields");
        }
        if (status == RunStatus.COMPLETED
                && (endedAt.isEmpty() || score.isEmpty() || endReason.isEmpty())) {
            throw new IllegalArgumentException("A completed run requires all terminal fields");
        }
        if ((status == RunStatus.ABORTED || status == RunStatus.UNKNOWN)
                && (endedAt.isEmpty() || score.isPresent() || endReason.isEmpty())) {
            throw new IllegalArgumentException(
                    "An aborted/unknown run requires an end time and reason but no score");
        }
    }
}
