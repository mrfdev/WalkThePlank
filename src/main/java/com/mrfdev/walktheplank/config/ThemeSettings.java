package com.mrfdev.walktheplank.config;

import java.util.List;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.Particle;

/** Resolved immutable platform appearance for one run configuration generation. */
public record ThemeSettings(
        String name,
        List<Material> parkourBlocks,
        boolean particlesEnabled,
        Particle particle,
        int particleCount) {
    public ThemeSettings {
        name = Objects.requireNonNull(name, "name");
        parkourBlocks = List.copyOf(parkourBlocks);
        Objects.requireNonNull(particle, "particle");
        if (name.isBlank()) {
            throw new IllegalArgumentException("theme name must not be blank");
        }
        if (parkourBlocks.isEmpty()) {
            throw new IllegalArgumentException("theme must contain at least one parkour block");
        }
        if (particleCount < 0 || particleCount > 1_000) {
            throw new IllegalArgumentException("theme particleCount must be between 0 and 1000");
        }
    }
}
