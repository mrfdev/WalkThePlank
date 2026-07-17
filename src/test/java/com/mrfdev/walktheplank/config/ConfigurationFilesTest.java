package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
