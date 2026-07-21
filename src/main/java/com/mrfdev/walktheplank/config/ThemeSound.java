package com.mrfdev.walktheplank.config;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.Sound;

/** One validated sound cue. Native sounds are resolved during configuration parsing. */
public record ThemeSound(
        boolean enabled,
        ThemeSoundProvider provider,
        String configuredSound,
        Optional<Sound> nativeSound,
        float volume,
        float pitch) {
    public ThemeSound {
        Objects.requireNonNull(provider, "provider");
        configuredSound = Objects.requireNonNull(configuredSound, "configuredSound");
        nativeSound = Objects.requireNonNull(nativeSound, "nativeSound");
        if (enabled && configuredSound.isBlank()) {
            throw new IllegalArgumentException("enabled sound must not be blank");
        }
        if (enabled && provider == ThemeSoundProvider.DEFAULT && nativeSound.isEmpty()) {
            throw new IllegalArgumentException("default sound must resolve through Paper's registry");
        }
        if (provider == ThemeSoundProvider.CMI && nativeSound.isPresent()) {
            throw new IllegalArgumentException("CMI sound must not carry a native Paper sound");
        }
        if (!Float.isFinite(volume) || volume < 0.0F || volume > 1.0F) {
            throw new IllegalArgumentException("sound volume must be between 0.0 and 1.0");
        }
        if (!Float.isFinite(pitch) || pitch < 0.5F || pitch > 2.0F) {
            throw new IllegalArgumentException("sound pitch must be between 0.5 and 2.0");
        }
    }
}
