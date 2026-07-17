package com.mrfdev.walktheplank.database;

import java.util.Objects;
import java.util.Optional;

/** Atomic result of retaining a run and, for positive scores, updating its leaderboard bests. */
public record CompletedRunResult(
        RunRecord run,
        Optional<ScoreUpdateResult> allTimeScore,
        Optional<ScoreUpdateResult> seasonScore,
        boolean created) {

    public CompletedRunResult {
        Objects.requireNonNull(run, "run");
        allTimeScore = Objects.requireNonNull(allTimeScore, "allTimeScore");
        seasonScore = Objects.requireNonNull(seasonScore, "seasonScore");
    }
}
