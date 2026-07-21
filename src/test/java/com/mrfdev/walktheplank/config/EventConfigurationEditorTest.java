package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class EventConfigurationEditorTest {
    @Test
    void writesTheNestedManualParticipationSwitchWithoutTouchingOtherValues() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("event.enabled", false);
        configuration.set("theme.active", "summer");

        EventConfigurationEditor.applyEnabled(configuration, true);

        assertTrue(configuration.getBoolean("event.enabled"));
        assertTrue(configuration.isString("theme.active"));

        EventConfigurationEditor.applyEnabled(configuration, false);
        assertFalse(configuration.getBoolean("event.enabled"));
    }
}
