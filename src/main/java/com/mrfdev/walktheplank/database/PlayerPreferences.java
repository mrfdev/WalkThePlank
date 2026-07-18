package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** UUID-owned accessibility preferences persisted independently from leaderboard scores. */
public record PlayerPreferences(
        UUID playerId,
        ParticlePreference particles,
        boolean soundsEnabled,
        boolean titlesEnabled,
        Instant updatedAt) {

    public PlayerPreferences {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(particles, "particles");
        updatedAt = PersistenceValidation.instant(updatedAt, "updatedAt");
    }

    public static PlayerPreferences defaults(UUID playerId) {
        return new PlayerPreferences(
                Objects.requireNonNull(playerId, "playerId"),
                ParticlePreference.FULL,
                true,
                true,
                Instant.EPOCH);
    }

    public PlayerPreferences withParticles(ParticlePreference preference, Instant changedAt) {
        return new PlayerPreferences(
                playerId,
                Objects.requireNonNull(preference, "preference"),
                soundsEnabled,
                titlesEnabled,
                changedAt);
    }

    public PlayerPreferences withSounds(boolean enabled, Instant changedAt) {
        return new PlayerPreferences(playerId, particles, enabled, titlesEnabled, changedAt);
    }

    public PlayerPreferences withTitles(boolean enabled, Instant changedAt) {
        return new PlayerPreferences(playerId, particles, soundsEnabled, enabled, changedAt);
    }
}
