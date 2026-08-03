package com.mrfdev.walktheplank.config;

/** Explicitly gated administrative run-testing limits. Disabled by default. */
public record AdminTestingSettings(
        boolean enabled,
        int maximumTargetScore) {
    public static final int MINIMUM_TARGET_SCORE = 1;
    public static final int MAXIMUM_TARGET_SCORE = 500;
    public static final int DEFAULT_MAXIMUM_TARGET_SCORE = 500;

    public AdminTestingSettings {
        if (maximumTargetScore < MINIMUM_TARGET_SCORE
                || maximumTargetScore > MAXIMUM_TARGET_SCORE) {
            throw new IllegalArgumentException(
                    "maximumTargetScore must be between "
                            + MINIMUM_TARGET_SCORE + " and " + MAXIMUM_TARGET_SCORE);
        }
    }

    public static AdminTestingSettings defaults() {
        return new AdminTestingSettings(false, DEFAULT_MAXIMUM_TARGET_SCORE);
    }
}
