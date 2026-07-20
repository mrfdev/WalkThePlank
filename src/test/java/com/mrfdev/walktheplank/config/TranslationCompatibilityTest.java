package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void legacyFileInheritsNewMenuActionSectionsWithoutRewrite() {
        YamlConfiguration bundledDefaults = new YamlConfiguration();
        bundledDefaults.set("mainGui.playerItem.item", "PLAYER_HEAD");
        bundledDefaults.set("mainGui.backItem.item", "ARROW");
        bundledDefaults.set("mainGui.closeItem.item", "BARRIER");
        YamlConfiguration legacyFile = new YamlConfiguration();
        legacyFile.setDefaults(bundledDefaults);

        assertNotNull(legacyFile.getConfigurationSection("mainGui.playerItem"));
        assertNotNull(legacyFile.getConfigurationSection("mainGui.backItem"));
        assertNotNull(legacyFile.getConfigurationSection("mainGui.closeItem"));
        assertFalse(legacyFile.contains("mainGui.playerItem.item", true));
        assertFalse(legacyFile.contains("mainGui.backItem.item", true));
        assertFalse(legacyFile.contains("mainGui.closeItem.item", true));
    }
}
