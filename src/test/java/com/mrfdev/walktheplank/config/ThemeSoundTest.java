package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class ThemeSoundTest {
    @Test
    void bundledCustomProfileHasEveryCueAtEightyPercentAndVanillaPitch() throws Exception {
        YamlConfiguration source = bundledConfig();
        for (ThemeSoundCue cue : ThemeSoundCue.values()) {
            String path = "sounds." + cue.configKey();
            assertTrue(source.getBoolean(path + ".enabled"));
            assertEquals("default", source.getString(path + ".provider"));
            assertEquals(0.8D, source.getDouble(path + ".volume"));
            assertEquals(1.0D, source.getDouble(path + ".pitch"));
        }
    }

    @Test
    void cmiProviderKeepsSafeTokenAndVolumePitch() throws Exception {
        YamlConfiguration source = bundledConfig();
        makeAllCmi(source);
        source.set("sounds.start.provider", "cmi");
        source.set("sounds.start.sound", "ENTITY_PLAYER_LEVELUP");
        source.set("sounds.start.volume", 0.8D);
        source.set("sounds.start.pitch", 1.25D);

        ThemeSound sound = ConfigurationManager.parseThemeSounds(source, "sounds")
                .get(ThemeSoundCue.START);

        assertEquals(ThemeSoundProvider.CMI, sound.provider());
        assertEquals("ENTITY_PLAYER_LEVELUP", sound.configuredSound());
        assertTrue(sound.nativeSound().isEmpty());
        assertEquals(0.8F, sound.volume());
        assertEquals(1.25F, sound.pitch());
    }

    @Test
    void cmiProviderRejectsCommandInjection() throws Exception {
        YamlConfiguration source = bundledConfig();
        makeAllCmi(source);
        source.set("sounds.start.provider", "cmi");
        source.set("sounds.start.sound", "pling player -all");

        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigurationManager.parseThemeSounds(source, "sounds"));
    }

    @Test
    void operatorPresetWithoutSoundSectionInheritsTopLevelProfile() throws Exception {
        YamlConfiguration source = bundledConfig();
        makeAllCmi(source);
        source.set("theme-presets.legacy.parkourBlocks", java.util.List.of("STONE"));
        source.set("theme-presets.legacy.particle.show", true);
        source.set("theme-presets.legacy.particle.type", "TOTEM_OF_UNDYING");
        source.set("theme-presets.legacy.particle.count", 12);

        ThemeSounds inherited = ConfigurationManager.parseThemeSounds(
                source, "theme-presets.legacy.sounds");

        assertEquals(
                ThemeSoundProvider.CMI,
                inherited.get(ThemeSoundCue.START).provider());
    }

    @Test
    void soundVolumeAndPitchAreBounded() throws Exception {
        YamlConfiguration invalidVolume = bundledConfig();
        makeAllCmi(invalidVolume);
        invalidVolume.set("sounds.start.volume", 1.01D);
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigurationManager.parseThemeSounds(invalidVolume, "sounds"));

        YamlConfiguration invalidPitch = bundledConfig();
        makeAllCmi(invalidPitch);
        invalidPitch.set("sounds.start.pitch", 2.01D);
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigurationManager.parseThemeSounds(invalidPitch, "sounds"));
    }

    private static void makeAllCmi(YamlConfiguration source) {
        for (ThemeSoundCue cue : ThemeSoundCue.values()) {
            String path = "sounds." + cue.configKey();
            source.set(path + ".provider", "cmi");
            source.set(path + ".sound", "ENTITY_PLAYER_LEVELUP");
        }
    }

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream stream = ThemeSoundTest.class.getResourceAsStream("/config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled config.yml");
            }
            YamlConfiguration source = new YamlConfiguration();
            source.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return source;
        }
    }
}
