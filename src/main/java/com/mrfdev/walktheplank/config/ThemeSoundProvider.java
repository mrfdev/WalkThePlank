package com.mrfdev.walktheplank.config;

import java.util.Locale;

/** Supported sound delivery paths. DEFAULT uses Paper directly; CMI dispatches /cmi sound. */
public enum ThemeSoundProvider {
    DEFAULT,
    CMI;

    public static ThemeSoundProvider parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("sound provider must be default or cmi");
        }
        return switch (configured.strip().toLowerCase(Locale.ROOT)) {
            case "default", "paper", "native" -> DEFAULT;
            case "cmi" -> CMI;
            default -> throw new IllegalArgumentException("sound provider must be default or cmi");
        };
    }
}
