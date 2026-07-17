package com.mrfdev.walktheplank.game;

import java.util.Locale;

public enum ArenaSelectionPolicy {
    RANDOM,
    ROUND_ROBIN,
    LEAST_RECENTLY_USED,
    PINNED;

    public static ArenaSelectionPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return RANDOM;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "arenaSelection.policy must be RANDOM, ROUND_ROBIN, LEAST_RECENTLY_USED, or PINNED",
                    exception);
        }
    }
}
