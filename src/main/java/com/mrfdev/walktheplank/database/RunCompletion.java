package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Idempotent terminal score/reason for a previously persisted run start. */
public record RunCompletion(
        UUID runId,
        Instant endedAt,
        int score,
        String endReason,
        List<RunCategoryScore> categoryScores) {

    public RunCompletion(UUID runId, Instant endedAt, int score, String endReason) {
        this(runId, endedAt, score, endReason, List.of());
    }

    public RunCompletion {
        Objects.requireNonNull(runId, "runId");
        endedAt = PersistenceValidation.instant(endedAt, "endedAt");
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative");
        }
        endReason = PersistenceValidation.text(endReason, "endReason", 64);
        categoryScores = List.copyOf(
                Objects.requireNonNull(categoryScores, "categoryScores"));
        Set<ScoreCategory> categories = new HashSet<>();
        for (RunCategoryScore categoryScore : categoryScores) {
            if (!categories.add(categoryScore.category())) {
                throw new IllegalArgumentException(
                        "categoryScores must not contain duplicate categories");
            }
            if (categoryScore.score() > score) {
                throw new IllegalArgumentException(
                        "category score must not exceed the Classic run score");
            }
        }
    }
}
