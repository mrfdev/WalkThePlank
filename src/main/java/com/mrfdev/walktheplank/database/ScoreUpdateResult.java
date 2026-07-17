package com.mrfdev.walktheplank.database;

import java.util.Objects;
import java.util.UUID;

/** Result of recording a completed parkour score. */
public record ScoreUpdateResult(
        UUID uuid,
        String username,
        int submittedScore,
        int previousBestScore,
        int bestScore,
        boolean newBest,
        boolean created) {

    public ScoreUpdateResult {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        if (submittedScore < 0 || previousBestScore < 0 || bestScore < 0) {
            throw new IllegalArgumentException("scores must not be negative");
        }
        if (bestScore < previousBestScore || bestScore < submittedScore) {
            throw new IllegalArgumentException("bestScore must include both old and submitted scores");
        }
        if (newBest != (submittedScore > previousBestScore)) {
            throw new IllegalArgumentException("newBest does not match the submitted score");
        }
    }
}
