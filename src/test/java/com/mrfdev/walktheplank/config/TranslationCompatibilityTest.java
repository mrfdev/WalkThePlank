package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class TranslationCompatibilityTest {
    @Test
    void legacyFileMayInheritNewGuiTitleColorWithoutRewrite() {
        YamlConfiguration bundledDefaults = new YamlConfiguration();
        bundledDefaults.set("mainGui.titleColor", "#111827");
        YamlConfiguration legacyFile = new YamlConfiguration();
        legacyFile.setDefaults(bundledDefaults);

        assertFalse(legacyFile.contains("mainGui.titleColor", true));
        assertTrue(ConfigurationManager.hasValidExplicitGuiTitleColor(
                legacyFile.get("mainGui.titleColor", true)));
    }

    @Test
    void explicitInvalidGuiTitleColorStillFailsValidation() {
        assertTrue(ConfigurationManager.hasValidExplicitGuiTitleColor("#111827"));
        assertTrue(ConfigurationManager.hasValidExplicitGuiTitleColor("#000000"));
        assertFalse(ConfigurationManager.hasValidExplicitGuiTitleColor("dark_blue"));
        assertFalse(ConfigurationManager.hasValidExplicitGuiTitleColor("#1234"));
    }
}
