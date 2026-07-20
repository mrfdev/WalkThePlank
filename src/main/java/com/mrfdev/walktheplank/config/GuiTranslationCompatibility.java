package com.mrfdev.walktheplank.config;

import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * In-memory upgrade for the exact GUI text shipped by the historical live plugin.
 *
 * <p>Operators keep control of genuinely customized translations. Only a complete, known legacy
 * item definition is replaced with the bundled modern definition, and the on-disk file is never
 * rewritten by this compatibility layer.</p>
 */
final class GuiTranslationCompatibility {
    private static final String LEGACY_WINDOW_TITLE = "&9&l1MB Walk the Plank game";
    private static final String LEGACY_TUTORIAL_TITLE = "&9&lHow to play?";
    private static final List<String> LEGACY_TUTORIAL_LORE = List.of(
            "&7 Your only mission is to jump on",
            "&7 the closest jack o lantern you see.",
            "&7 After you jump, look around and",
            "&7 find the next one. Have fun!",
            "&b-- no elytra, pearls, potions, etc --",
            "&f To start playing, click",
            "&f on the &bDiamond Boots&f.");
    private static final String LEGACY_PLAY_TITLE = "&9&lPlay";
    private static final List<String> LEGACY_PLAY_LORE = List.of(
            "&7 Once you click play the game ",
            "&7 will start and you can play.",
            "&7 To leave, or finish your game,",
            "&7 simply go back /home");
    private static final String LEGACY_SCOREBOARD_TITLE = "&9&lScoreboard";
    private static final String LEGACY_SCOREBOARD_RECORD =
            "&f{{index}}. &9{{playerName}} &7({{score}})";
    private static final List<String> LEGACY_SCOREBOARD_LORE = List.of(
            "{{scoreboard}}",
            " ",
            "&7Be proud of yourself",
            "&7if you are up there!",
            " ",
            "&fClick to find out your position.");

    private GuiTranslationCompatibility() {
    }

    static void upgrade(
            YamlConfiguration translations,
            YamlConfiguration bundledDefaults) {
        Objects.requireNonNull(translations, "translations");
        Objects.requireNonNull(bundledDefaults, "bundledDefaults");

        if (LEGACY_WINDOW_TITLE.equals(explicitString(translations, "mainGui.title"))) {
            copyString(translations, bundledDefaults, "mainGui.title");
        }
        if (matchesItem(
                translations,
                "mainGui.tutorialItem",
                LEGACY_TUTORIAL_TITLE,
                LEGACY_TUTORIAL_LORE)) {
            copyString(translations, bundledDefaults, "mainGui.tutorialItem.title");
            copyStringList(translations, bundledDefaults, "mainGui.tutorialItem.lore");
        }
        if (matchesItem(
                translations,
                "mainGui.playItem",
                LEGACY_PLAY_TITLE,
                LEGACY_PLAY_LORE)) {
            copyString(translations, bundledDefaults, "mainGui.playItem.title");
            copyStringList(translations, bundledDefaults, "mainGui.playItem.lore");
        }
        if (matchesItem(
                        translations,
                        "mainGui.scoreboardItem",
                        LEGACY_SCOREBOARD_TITLE,
                        LEGACY_SCOREBOARD_LORE)
                && LEGACY_SCOREBOARD_RECORD.equals(
                        explicitString(
                                translations,
                                "mainGui.scoreboardItem.scoreboardRecord"))) {
            copyString(translations, bundledDefaults, "mainGui.scoreboardItem.item");
            copyString(translations, bundledDefaults, "mainGui.scoreboardItem.title");
            copyString(
                    translations,
                    bundledDefaults,
                    "mainGui.scoreboardItem.scoreboardRecord");
            copyStringList(
                    translations,
                    bundledDefaults,
                    "mainGui.scoreboardItem.lore");
        }
    }

    private static boolean matchesItem(
            YamlConfiguration translations,
            String path,
            String title,
            List<String> lore) {
        return title.equals(explicitString(translations, path + ".title"))
                && lore.equals(explicitStringList(translations, path + ".lore"));
    }

    private static String explicitString(
            YamlConfiguration translations,
            String path) {
        Object value = translations.get(path, true);
        return value instanceof String text ? text : null;
    }

    private static List<String> explicitStringList(
            YamlConfiguration translations,
            String path) {
        Object value = translations.get(path, true);
        if (!(value instanceof List<?> values)
                || values.stream().anyMatch(element -> !(element instanceof String))) {
            return List.of();
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static void copyString(
            YamlConfiguration translations,
            YamlConfiguration bundledDefaults,
            String path) {
        translations.set(path, bundledDefaults.getString(path));
    }

    private static void copyStringList(
            YamlConfiguration translations,
            YamlConfiguration bundledDefaults,
            String path) {
        translations.set(path, bundledDefaults.getStringList(path));
    }
}
