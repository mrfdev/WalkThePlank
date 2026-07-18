package com.mrfdev.walktheplank.database;

import java.util.Objects;

/** One independently ranked category result attached atomically to a completed run. */
public record RunCategoryScore(ScoreCategory category, int score) {
    public RunCategoryScore {
        Objects.requireNonNull(category, "category");
        if (score < 1) {
            throw new IllegalArgumentException("category score must be positive");
        }
    }
}
