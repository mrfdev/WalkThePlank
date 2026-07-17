package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One retained completed run with its optional season and reward-ledger association. */
public record RunRecord(
        long sequence,
        UUID id,
        UUID playerId,
        String username,
        String arenaId,
        Instant startedAt,
        Optional<Instant> endedAt,
        Optional<Integer> score,
        Optional<String> endReason,
        String release,
        Optional<UUID> seasonId,
        Optional<UUID> rewardPlanId,
        RunStatus status) {

    public RunRecord {
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        username = PersistenceValidation.playerName(username);
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
        rewardPlanId = Objects.requireNonNull(rewardPlanId, "rewardPlanId");
        Objects.requireNonNull(status, "status");
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
