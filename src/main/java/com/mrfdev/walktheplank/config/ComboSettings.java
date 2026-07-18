package com.mrfdev.walktheplank.config;

import java.time.Duration;
import java.util.Objects;

/** Timing definition for the separate Combo and Flawless categories. */
public record ComboSettings(boolean enabled, Duration maximumGap) {
    public ComboSettings {
        Objects.requireNonNull(maximumGap, "maximumGap");
        if (maximumGap.compareTo(Duration.ofSeconds(1)) < 0
                || maximumGap.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(
                    "combo maximumGap must be between one and sixty seconds");
        }
    }
}
