package com.mrfdev.walktheplank.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

final class MenuAppearanceTest {
    @Test
    void replacesLegacyTitleFormattingWithReadableHexColor() {
        Component legacy = Component.text("1MB ", NamedTextColor.DARK_BLUE)
                .append(Component.text("Walk the Plank game", NamedTextColor.BLUE));

        Component title = MenuAppearance.readableTitle(legacy, "#111827");

        assertEquals(
                "1MB Walk the Plank game",
                PlainTextComponentSerializer.plainText().serialize(title));
        assertEquals(TextColor.color(0x111827), title.color());
        assertEquals(List.of(), title.children());
    }

    @Test
    void defaultsBlankTitleAndMigratesLegacyWhitePane() {
        Component title = MenuAppearance.readableTitle(Component.empty(), "#000000");

        assertEquals(
                MenuAppearance.DEFAULT_TITLE,
                PlainTextComponentSerializer.plainText().serialize(title));
        assertEquals(TextColor.color(0x000000), title.color());
        assertEquals(
                MenuAppearance.DEFAULT_BORDER_MATERIAL,
                MenuAppearance.borderMaterial("white_stained_glass_pane"));
        assertEquals(
                MenuAppearance.DEFAULT_BORDER_MATERIAL,
                MenuAppearance.borderMaterial(null));
    }

    @Test
    void rejectsAmbiguousTitleColors() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuAppearance.readableTitle(Component.text("Title"), "dark_blue"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuAppearance.readableTitle(Component.text("Title"), "#1234"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuAppearance.borderMaterial("STONE"));
    }
}
