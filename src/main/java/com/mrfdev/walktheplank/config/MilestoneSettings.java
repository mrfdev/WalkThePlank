package com.mrfdev.walktheplank.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Particle;
import org.bukkit.Sound;

/** Validated native milestone-feedback configuration. */
public record MilestoneSettings(
        boolean enabled,
        List<Integer> scores,
        Sound sound,
        Particle particle,
        int particleCount,
        Duration cooldown) {

    public MilestoneSettings {
        scores = List.copyOf(Objects.requireNonNull(scores, "scores"));
        Objects.requireNonNull(sound, "sound");
        Objects.requireNonNull(particle, "particle");
        Objects.requireNonNull(cooldown, "cooldown");
        if (scores.size() > 64) {
            throw new IllegalArgumentException("milestone scores must not exceed 64 entries");
        }
        Set<Integer> unique = new LinkedHashSet<>();
        for (int score : scores) {
            if (score < 1 || score > 1_000_000 || !unique.add(score)) {
                throw new IllegalArgumentException(
                        "milestone scores must be unique values between 1 and 1000000");
            }
        }
        scores = List.copyOf(unique.stream().sorted().toList());
        if (particle.getDataType() != Void.class) {
            throw new IllegalArgumentException("milestone particle must not require typed data");
        }
        if (particleCount < 0 || particleCount > 1_000) {
            throw new IllegalArgumentException(
                    "milestone particleCount must be between 0 and 1000");
        }
        if (cooldown.isNegative() || cooldown.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(
                    "milestone cooldown must be between zero and one minute");
        }
    }

    public boolean isMilestone(int score) {
        return enabled && java.util.Collections.binarySearch(scores, score) >= 0;
    }
}
