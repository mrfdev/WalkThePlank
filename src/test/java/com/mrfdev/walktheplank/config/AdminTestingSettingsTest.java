package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class AdminTestingSettingsTest {
    @Test
    void defaultsRemainDisabledAndBounded() {
        AdminTestingSettings settings = AdminTestingSettings.defaults();

        assertFalse(settings.enabled());
        assertEquals(500, settings.maximumTargetScore());
    }

    @Test
    void parserUsesDefaultsAndAcceptsAnExplicitBound() {
        assertEquals(
                AdminTestingSettings.defaults(),
                ConfigurationManager.parseAdminTestingSettings(new YamlConfiguration()));

        YamlConfiguration source = new YamlConfiguration();
        source.set("adminTesting.enabled", true);
        source.set("adminTesting.maximumTargetScore", 110);

        AdminTestingSettings settings =
                ConfigurationManager.parseAdminTestingSettings(source);
        assertTrue(settings.enabled());
        assertEquals(110, settings.maximumTargetScore());
    }

    @Test
    void rejectsTargetsOutsideTheOperationalLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminTestingSettings(
                        false,
                        AdminTestingSettings.MINIMUM_TARGET_SCORE - 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminTestingSettings(
                        true,
                        AdminTestingSettings.MAXIMUM_TARGET_SCORE + 1));
    }

    @Test
    void configurationShapeRecognizesOnlyTheSupportedTestingKeys() {
        assertTrue(ConfigurationManager.isKnownConfigKey("adminTesting"));
        assertTrue(ConfigurationManager.isKnownConfigKey("adminTesting.enabled"));
        assertTrue(ConfigurationManager.isKnownConfigKey(
                "adminTesting.maximumTargetScore"));
        assertFalse(ConfigurationManager.isKnownConfigKey("adminTesting.score"));
    }
}
