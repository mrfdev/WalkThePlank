package com.mrfdev.walktheplank.database;

/** Outcome of pruning the bounded run/reward history. */
public record RetentionResult(int retainedLimit, long deletedRuns, long remainingRuns) {
    public RetentionResult {
        if (retainedLimit < 0 || deletedRuns < 0L || remainingRuns < 0L) {
            throw new IllegalArgumentException("Retention counts must not be negative");
        }
    }
}
