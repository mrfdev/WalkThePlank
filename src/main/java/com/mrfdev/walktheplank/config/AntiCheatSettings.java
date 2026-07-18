package com.mrfdev.walktheplank.config;

import java.time.Duration;
import java.util.Objects;

/** Conservative run-integrity controls; violations end or block a run but never auto-ban. */
public record AntiCheatSettings(
        boolean enabled,
        boolean blockProjectiles,
        boolean blockRiptide,
        boolean blockExploitTeleports,
        Duration minimumJumpInterval,
        Duration anomalyAuditCooldown) {

    public AntiCheatSettings {
        Objects.requireNonNull(minimumJumpInterval, "minimumJumpInterval");
        Objects.requireNonNull(anomalyAuditCooldown, "anomalyAuditCooldown");
        if (minimumJumpInterval.isNegative()
                || minimumJumpInterval.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException(
                    "minimumJumpInterval must be between zero and two seconds");
        }
        if (anomalyAuditCooldown.isNegative()
                || anomalyAuditCooldown.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "anomalyAuditCooldown must be between zero and ten minutes");
        }
    }
}
