package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "winter",
                new ExpectedTheme(
                        java.util.List.of("SNOW_BLOCK", "WHITE_CONCRETE"),
                        Particle.SNOWFLAKE,
                        16),
                "valentine",
                new ExpectedTheme(
                        java.util.List.of("PINK_CONCRETE", "RED_CONCRETE"),
                        Particle.HEART,
                        6));

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
