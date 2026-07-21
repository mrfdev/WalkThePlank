package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class ThemePresetTest {
    @Test
    void bundledPresetsExposeTheExpectedBlocksAndUntypedParticles() throws Exception {
        YamlConfiguration source = bundledConfig();
        Map<String, ExpectedTheme> expected = Map.of(
                "default",
                new ExpectedTheme(
                        java.util.List.of("EMERALD_BLOCK"),
                        Particle.TOTEM_OF_UNDYING,
                        12),
                "valentine",
                new ExpectedTheme(
                        java.util.List.of("PINK_CONCRETE", "RED_CONCRETE"),
                        Particle.HEART,
                        6),
                "easter",
                new ExpectedTheme(
                        java.util.List.of(
                                "LIGHT_BLUE_CONCRETE", "YELLOW_CONCRETE", "PINK_CONCRETE"),
                        Particle.HAPPY_VILLAGER,
                        10),
                "summer",
                new ExpectedTheme(
                        java.util.List.of("PINK_CONCRETE"),
                        Particle.CHERRY_LEAVES,
                        14),
                "halloween",
                new ExpectedTheme(
                        java.util.List.of("JACK_O_LANTERN"),
                        Particle.TOTEM_OF_UNDYING,
                        12),
                "thanksgiving",
                new ExpectedTheme(
                        java.util.List.of(
                                "ORANGE_CONCRETE", "BROWN_CONCRETE", "YELLOW_CONCRETE"),
                        Particle.COMPOSTER,
                        12),
                "christmas",
                new ExpectedTheme(
                        java.util.List.of("SNOW_BLOCK", "WHITE_CONCRETE"),
                        Particle.SNOWFLAKE,
                        16),
                "anniversary",
                new ExpectedTheme(
                        java.util.List.of("GOLD_BLOCK", "DIAMOND_BLOCK"),
                        Particle.TOTEM_OF_UNDYING,
                        18));

        assertEquals(
                expected.keySet(),
                source.getConfigurationSection("theme-presets").getKeys(false));
        assertFalse(source.getBoolean("event.enabled"));

        for (Map.Entry<String, ExpectedTheme> entry : expected.entrySet()) {
            source.set("theme.active", entry.getKey());
            String active = ConfigurationManager.resolveActiveThemeName(source);
            String path = "theme-presets." + active;

            assertEquals(entry.getKey(), active);
            assertEquals(
                    entry.getValue().blocks(),
                    source.getStringList(path + ".parkourBlocks"));
            Particle particle = Particle.valueOf(
                    source.getString(path + ".particle.type"));
            assertEquals(entry.getValue().particle(), particle);
            assertEquals(Void.class, particle.getDataType());
            assertEquals(
                    entry.getValue().count(),
                    source.getInt(path + ".particle.count"));
            for (ThemeSoundCue cue : ThemeSoundCue.values()) {
                String cuePath = path + ".sounds." + cue.configKey();
                assertEquals(true, source.getBoolean(cuePath + ".enabled"));
                assertEquals("default", source.getString(cuePath + ".provider"));
                assertEquals(0.8D, source.getDouble(cuePath + ".volume"));
            }
        }
    }

    @Test
    void customThemeSelectsTheLegacyTopLevelAppearance() throws Exception {
        YamlConfiguration source = bundledConfig();
        source.set("theme.active", "custom");

        assertEquals(
                "custom",
                ConfigurationManager.resolveActiveThemeName(source));
    }

    @Test
    void activeThemeMustNameCustomOrAnExistingPreset() throws Exception {
        YamlConfiguration source = bundledConfig();
        source.set("theme.active", "missing");

        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigurationManager.resolveActiveThemeName(source));
    }

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream stream =
                ThemePresetTest.class.getResourceAsStream("/config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled config.yml");
            }
            YamlConfiguration source = new YamlConfiguration();
            source.loadFromString(new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8));
            return source;
        }
    }

    private record ExpectedTheme(
            java.util.List<String> blocks,
            Particle particle,
            int count) {
    }
}
