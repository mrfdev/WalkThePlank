package com.mrfdev.walktheplank.config;

import java.util.Locale;

/** Player-facing sound moments supported by every appearance preset. */
public enum ThemeSoundCue {
    START,
    LANDING,
    MILESTONE,
    COMBO,
    FINISH,
    FAILURE;

    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
