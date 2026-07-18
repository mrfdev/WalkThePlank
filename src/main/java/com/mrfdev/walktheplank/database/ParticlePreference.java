package com.mrfdev.walktheplank.database;

import java.util.Locale;
import java.util.Objects;

/** Per-player particle visibility for WalkThePlank-owned cosmetic effects. */
public enum ParticlePreference {
    FULL(1.0D),
    REDUCED(0.25D),
    OFF(0.0D);

    private final double multiplier;

    ParticlePreference(double multiplier) {
        this.multiplier = multiplier;
    }

    public int apply(int configuredCount) {
        if (configuredCount < 0) {
            throw new IllegalArgumentException("configuredCount must not be negative");
        }
        if (configuredCount == 0 || this == OFF) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(configuredCount * multiplier));
    }

    public static ParticlePreference parse(String value) {
        Objects.requireNonNull(value, "value");
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
