package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class GuiTranslationCompatibilityTest {
    @Test
    void upgradesTheExactHistoricalGuiTextInMemory() {
        YamlConfiguration legacy = legacyTranslations();
        YamlConfiguration defaults = bundledDefaults();

        GuiTranslationCompatibility.upgrade(legacy, defaults);

        assertEquals(
                defaults.getString("mainGui.title"),
                legacy.getString("mainGui.title"));
        assertEquals(
                defaults.getString("mainGui.tutorialItem.title"),
                legacy.getString("mainGui.tutorialItem.title"));
        assertEquals(
                defaults.getStringList("mainGui.tutorialItem.lore"),
                legacy.getStringList("mainGui.tutorialItem.lore"));
        assertEquals(
                defaults.getStringList("mainGui.playItem.lore"),
                legacy.getStringList("mainGui.playItem.lore"));
        assertEquals(
                defaults.getString("mainGui.scoreboardItem.scoreboardRecord"),
                legacy.getString("mainGui.scoreboardItem.scoreboardRecord"));
        assertTrue(legacy.getStringList("mainGui.tutorialItem.lore").stream()
                .filter(line -> !line.isEmpty())
                .allMatch(line -> line.startsWith("minimessage:<!italic>")));
    }

    @Test
    void preservesAGenuinelyCustomizedGuiItem() {
        YamlConfiguration legacy = legacyTranslations();
        YamlConfiguration defaults = bundledDefaults();
        List<String> customLore = List.of("Custom operator tutorial");
        legacy.set("mainGui.tutorialItem.lore", customLore);

        GuiTranslationCompatibility.upgrade(legacy, defaults);

        assertEquals("&9&lHow to play?", legacy.getString("mainGui.tutorialItem.title"));
        assertEquals(customLore, legacy.getStringList("mainGui.tutorialItem.lore"));
    }

    @Test
    void upgradesThePreviousBundledTutorialToTheDynamicPlatformLabel() {
        YamlConfiguration previous = bundledDefaults();
        List<String> previousLore = previous.getStringList("mainGui.tutorialItem.lore")
                .stream()
                .map(line -> line.replace(
                        "Land on the nearest {{platformBlock}}.",
                        "Land on the nearest glowing platform."))
                .toList();
        previous.set("mainGui.tutorialItem.lore", previousLore);
        YamlConfiguration current = bundledDefaults();

        GuiTranslationCompatibility.upgrade(previous, current);

        assertEquals(
                current.getStringList("mainGui.tutorialItem.lore"),
                previous.getStringList("mainGui.tutorialItem.lore"));
        assertTrue(previous.getStringList("mainGui.tutorialItem.lore")
                .contains(
                        "minimessage:<!italic><color:#f2f5f7>Land on the nearest {{platformBlock}}.</color>"));
    }

    @Test
    void tutorialValidationAllowsOnlyTheRuntimePlatformLabel() {
        assertEquals(
                Set.of("platformBlock"),
                ConfigurationManager.allowedTranslationPlaceholders(
                        "mainGui.tutorialItem.lore"));
    }

    private static YamlConfiguration legacyTranslations() {
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("mainGui.title", "&9&l1MB Walk the Plank game");
        legacy.set("mainGui.tutorialItem.title", "&9&lHow to play?");
        legacy.set("mainGui.tutorialItem.lore", List.of(
                "&7 Your only mission is to jump on",
                "&7 the closest jack o lantern you see.",
                "&7 After you jump, look around and",
                "&7 find the next one. Have fun!",
                "&b-- no elytra, pearls, potions, etc --",
                "&f To start playing, click",
                "&f on the &bDiamond Boots&f."));
        legacy.set("mainGui.playItem.title", "&9&lPlay");
        legacy.set("mainGui.playItem.lore", List.of(
                "&7 Once you click play the game ",
                "&7 will start and you can play.",
                "&7 To leave, or finish your game,",
                "&7 simply go back /home"));
        legacy.set("mainGui.scoreboardItem.item", "SIGN");
        legacy.set("mainGui.scoreboardItem.title", "&9&lScoreboard");
        legacy.set(
                "mainGui.scoreboardItem.scoreboardRecord",
                "&f{{index}}. &9{{playerName}} &7({{score}})");
        legacy.set("mainGui.scoreboardItem.lore", List.of(
                "{{scoreboard}}",
                " ",
                "&7Be proud of yourself",
                "&7if you are up there!",
                " ",
                "&fClick to find out your position."));
        return legacy;
    }

    private static YamlConfiguration bundledDefaults() {
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            defaults.load(new InputStreamReader(
                    Objects.requireNonNull(
                            GuiTranslationCompatibilityTest.class.getResourceAsStream(
                                    "/translations.yml")),
                    StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError("Could not load bundled translations", exception);
        }
        return defaults;
    }
}
