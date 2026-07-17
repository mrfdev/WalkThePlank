package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlitePathGuardTest {
    @Test
    void acceptsExistingRegularFile(@TempDir Path dataFolder) throws Exception {
        Path database = Files.createFile(dataFolder.resolve("database.db"));

        assertEquals(database, SqlitePathGuard.resolve(dataFolder, "database.db"));
    }

    @Test
    void acceptsAbsentTargetWithExistingWritableParent(@TempDir Path dataFolder) throws Exception {
        Path databases = Files.createDirectory(dataFolder.resolve("databases"));

        assertEquals(
                databases.resolve("event.db"),
                SqlitePathGuard.resolve(dataFolder, "databases/event.db"));
    }

    @Test
    void rejectsExistingDirectoryAsDatabase(@TempDir Path dataFolder) throws Exception {
        Files.createDirectory(dataFolder.resolve("database.db"));

        assertThrows(
                IllegalArgumentException.class,
                () -> SqlitePathGuard.resolve(dataFolder, "database.db"));
    }

    @Test
    void rejectsAbsentImmediateParent(@TempDir Path dataFolder) {
        assertThrows(
                IllegalArgumentException.class,
                () -> SqlitePathGuard.resolve(dataFolder, "missing/database.db"));
    }

    @Test
    void rejectsLexicalEscape(@TempDir Path dataFolder) {
        assertThrows(
                IllegalArgumentException.class,
                () -> SqlitePathGuard.resolve(dataFolder, "../database.db"));
    }

    @Test
    void rejectsSymbolicLinkPathSegment(@TempDir Path directory) throws Exception {
        Path dataFolder = Files.createDirectory(directory.resolve("data"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.createSymbolicLink(dataFolder.resolve("linked"), outside);

        assertThrows(
                IllegalArgumentException.class,
                () -> SqlitePathGuard.resolve(dataFolder, "linked/database.db"));
    }

    @Test
    void rejectsSymbolicLinkDataFolder(@TempDir Path directory) throws Exception {
        Path realDataFolder = Files.createDirectory(directory.resolve("real-data"));
        Path linkedDataFolder = directory.resolve("linked-data");
        Files.createSymbolicLink(linkedDataFolder, realDataFolder);

        assertThrows(
                IllegalArgumentException.class,
                () -> SqlitePathGuard.resolve(linkedDataFolder, "database.db"));
    }
}
