package com.mrfdev.walktheplank.gui;

import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class MenuAppearance {
    static final String DEFAULT_TITLE = "1MB Walk the Plank";
    static final String DEFAULT_TITLE_COLOR = "#111827";
    static final String DEFAULT_BORDER_MATERIAL = "LIGHT_BLUE_STAINED_GLASS_PANE";
    private static final String LEGACY_BORDER_MATERIAL = "WHITE_STAINED_GLASS_PANE";

    private MenuAppearance() {
    }

    static Component readableTitle(Component configuredTitle, String configuredColor) {
        String plainTitle = PlainTextComponentSerializer.plainText()
                .serialize(Objects.requireNonNull(configuredTitle, "configuredTitle"))
                .strip();
        if (plainTitle.isBlank()) {
            plainTitle = DEFAULT_TITLE;
        }
        return Component.text(plainTitle, parseHexColor(configuredColor));
    }

    static TextColor parseHexColor(String configuredColor) {
        String normalized = Objects.requireNonNullElse(configuredColor, DEFAULT_TITLE_COLOR).strip();
        if (!normalized.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("GUI title color must use #RRGGBB notation");
        }
        return TextColor.color(Integer.parseInt(normalized.substring(1), 16));
    }

    static String borderMaterial(String configuredMaterial) {
        String normalized = Objects.requireNonNullElse(configuredMaterial, DEFAULT_BORDER_MATERIAL)
                .strip()
                .toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || LEGACY_BORDER_MATERIAL.equals(normalized)) {
            return DEFAULT_BORDER_MATERIAL;
        }
        if (!normalized.equals("GLASS_PANE")
                && !normalized.endsWith("_STAINED_GLASS_PANE")) {
            throw new IllegalArgumentException("GUI border material must be a glass pane item");
        }
        return normalized;
    }
}
