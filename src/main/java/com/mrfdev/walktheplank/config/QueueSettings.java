package com.mrfdev.walktheplank.config;

import java.time.Duration;
import java.util.Objects;

/** Validated player queue timing and behavior. */
public record QueueSettings(
        boolean enabled,
        Duration joinCooldown,
        Duration readinessWindow,
        Duration reminderInterval) {
    public QueueSettings {
        joinCooldown = bounded(
                joinCooldown, "joinCooldown", Duration.ZERO, Duration.ofMinutes(10));
        readinessWindow = bounded(
                readinessWindow, "readinessWindow", Duration.ofSeconds(5), Duration.ofMinutes(5));
        reminderInterval = bounded(
                reminderInterval, "reminderInterval", Duration.ofSeconds(2), Duration.ofMinutes(1));
        if (reminderInterval.compareTo(readinessWindow) > 0) {
            throw new IllegalArgumentException("reminderInterval cannot exceed readinessWindow");
        }
    }

    public static QueueSettings defaults() {
        return new QueueSettings(
                true,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                Duration.ofSeconds(5));
    }

    private static Duration bounded(
            Duration value,
            String name,
            Duration minimum,
            Duration maximum) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside its supported range");
        }
        return value;
    }
}
