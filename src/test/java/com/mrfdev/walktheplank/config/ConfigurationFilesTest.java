package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigurationFilesTest {
    @Test
    void captureClonesInputAndAccessorArrays() {
        byte[] config = {1, 2, 3};
        byte[] translations = {4, 5, 6};
        ConfigurationManager.ConfigurationFiles files =
                new ConfigurationManager.ConfigurationFiles(config, translations);

        config[0] = 9;
        translations[0] = 9;
        byte[] returnedConfig = files.configBytes();
        byte[] returnedTranslations = files.translationBytes();
        returnedConfig[1] = 9;
        returnedTranslations[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, files.configBytes());
        assertArrayEquals(new byte[] {4, 5, 6}, files.translationBytes());
    }

    @Test
    void safeFingerprintTracksInvestigationPermissionRemapping() {
        YamlConfiguration original = new YamlConfiguration();
        original.set(
                "permissions.adminInvestigate",
                "infinityparkour.admin.investigate");
        YamlConfiguration remapped = new YamlConfiguration();
        remapped.set(
                "permissions.adminInvestigate",
                "example.staff.investigate");

        assertNotEquals(
                SafeConfigFingerprint.fingerprint(original),
                SafeConfigFingerprint.fingerprint(remapped));
    }
}
