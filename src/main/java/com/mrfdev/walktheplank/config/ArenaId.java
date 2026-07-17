package com.mrfdev.walktheplank.config;

import java.util.Objects;
import java.util.regex.Pattern;

/** Safe, stable arena identifiers accepted by the in-game editor. */
public final class ArenaId {
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private ArenaId() {
    }

    public static String requireValid(String candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!VALID_ID.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "Arena IDs must be 1-32 lowercase characters using only a-z, 0-9, _ or -");
        }
        return candidate;
    }

    public static boolean isValid(String candidate) {
        return candidate != null && VALID_ID.matcher(candidate).matches();
    }
}
