package com.mrfdev.walktheplank.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Complete immutable sound profile for an active theme. */
public record ThemeSounds(Map<ThemeSoundCue, ThemeSound> cues) {
    public ThemeSounds {
        Objects.requireNonNull(cues, "cues");
        EnumMap<ThemeSoundCue, ThemeSound> copy = new EnumMap<>(ThemeSoundCue.class);
        copy.putAll(cues);
        for (ThemeSoundCue cue : ThemeSoundCue.values()) {
            if (!copy.containsKey(cue)) {
                throw new IllegalArgumentException("theme sound profile is missing " + cue.configKey());
            }
        }
        cues = Collections.unmodifiableMap(copy);
    }

    public ThemeSound get(ThemeSoundCue cue) {
        return cues.get(Objects.requireNonNull(cue, "cue"));
    }
}
