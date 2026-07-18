package com.mrfdev.walktheplank.database;

import java.util.Locale;
import java.util.Objects;

/** Score categories stored separately from the historical Classic leaderboard. */
public enum ScoreCategory {
    COMBO,
    FLAWLESS;

    public static ScoreCategory parse(String value) {
        Objects.requireNonNull(value, "value");
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
