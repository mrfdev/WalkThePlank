package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AtomicConfigFileTest {
    @Test
    void replacementCreatesBackupAndCanRestoreIt(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "version: original\n", StandardCharsets.UTF_8);
        AtomicConfigFile file = new AtomicConfigFile(config);

        AtomicConfigFile.CommitToken commit =
                file.replaceWithBackup(Files.readAllBytes(config), "version: candidate\n");

        assertEquals("version: candidate\n", Files.readString(config, StandardCharsets.UTF_8));
        Path backup = directory.resolve("config.yml.backup");
        assertTrue(Files.isRegularFile(backup));
        assertEquals("version: original\n", Files.readString(backup, StandardCharsets.UTF_8));

        file.restoreBackup(commit);
        assertEquals("version: original\n", Files.readString(config, StandardCharsets.UTF_8));
    }

    @Test
    void refusesToRollbackOverAConfigurationChangedAfterCommit(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "version: original\n", StandardCharsets.UTF_8);
        AtomicConfigFile file = new AtomicConfigFile(config);
        AtomicConfigFile.CommitToken commit =
                file.replaceWithBackup(Files.readAllBytes(config), "version: candidate\n");
        Files.writeString(config, "version: newer-manual-edit\n", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class, () -> file.restoreBackup(commit));

        assertEquals("version: newer-manual-edit\n", Files.readString(config, StandardCharsets.UTF_8));
    }

    @Test
    void refusesToOverwriteAConfigurationChangedSinceLoad(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "version: original\n", StandardCharsets.UTF_8);
        byte[] loaded = Files.readAllBytes(config);
        Files.writeString(config, "version: manually-edited\n", StandardCharsets.UTF_8);

        AtomicConfigFile file = new AtomicConfigFile(config);
        assertThrows(
                java.io.IOException.class,
                () -> file.replaceWithBackup(loaded, "version: candidate\n"));

        assertEquals("version: manually-edited\n", Files.readString(config, StandardCharsets.UTF_8));
    }
}
