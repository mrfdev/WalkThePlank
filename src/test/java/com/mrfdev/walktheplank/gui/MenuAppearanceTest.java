package com.mrfdev.walktheplank.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
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

    @Test
    void removesItalicsFromEveryTooltipComponentBranch() {
        Component configured = Component.text("Name")
                .decoration(TextDecoration.ITALIC, true)
                .append(Component.text(" nested")
                        .decoration(TextDecoration.ITALIC, true)
                        .append(Component.text(" deep")
                                .decoration(TextDecoration.ITALIC, true)));

        Component styled = MenuAppearance.nonItalic(configured);

        assertNoItalics(styled);
        assertEquals(
                "Name nested deep",
                PlainTextComponentSerializer.plainText().serialize(styled));
    }

    @Test
    void describesConfiguredPlatformMaterialsInReadableEnglish() {
        assertEquals(
                "emerald block",
                MenuAppearance.platformBlockLabel(List.of(Material.EMERALD_BLOCK)));
        assertEquals(
                "jack o'lantern",
                MenuAppearance.platformBlockLabel(List.of(Material.JACK_O_LANTERN)));
        assertEquals(
                "emerald block or diamond block",
                MenuAppearance.platformBlockLabel(List.of(
                        Material.EMERALD_BLOCK,
                        Material.DIAMOND_BLOCK)));
        assertEquals(
                "emerald block, diamond block, or gold block",
                MenuAppearance.platformBlockLabel(List.of(
                        Material.EMERALD_BLOCK,
                        Material.DIAMOND_BLOCK,
                        Material.GOLD_BLOCK)));
        assertEquals(
                "emerald block",
                MenuAppearance.platformBlockLabel(List.of(
                        Material.EMERALD_BLOCK,
                        Material.EMERALD_BLOCK)));
        assertEquals("platform", MenuAppearance.platformBlockLabel(List.of()));
        assertEquals(
                "configured platform",
                MenuAppearance.platformBlockLabel(List.of(
                        Material.EMERALD_BLOCK,
                        Material.DIAMOND_BLOCK,
                        Material.GOLD_BLOCK,
                        Material.IRON_BLOCK)));
    }

    private static void assertNoItalics(Component component) {
        assertEquals(
                TextDecoration.State.FALSE,
                component.decoration(TextDecoration.ITALIC));
        component.children().forEach(MenuAppearanceTest::assertNoItalics);
    }
}
