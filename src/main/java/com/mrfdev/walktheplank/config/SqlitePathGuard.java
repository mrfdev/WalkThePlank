package com.mrfdev.walktheplank.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves SQLite storage without permitting escape, links, or unusable targets. */
final class SqlitePathGuard {
    private SqlitePathGuard() {
    }

    static Path resolve(Path pluginDataFolder, String configuredFile) {
        Objects.requireNonNull(pluginDataFolder, "pluginDataFolder");
        if (configuredFile == null || configuredFile.isBlank()) {
            throw new IllegalArgumentException(
                    "database.sqlite.file must be a nonblank relative file name");
        }

        Path relative;
        try {
            relative = Path.of(configuredFile);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(
                    "database.sqlite.file is not a valid confined path", exception);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException(
                    "database.sqlite.file must be relative to the plugin data folder");
        }

        Path dataFolder = pluginDataFolder.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(dataFolder)
                || !Files.isDirectory(dataFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Plugin data folder must be a safe regular directory");
        }
        Path databaseFile = dataFolder.resolve(relative).normalize();
        if (!databaseFile.startsWith(dataFolder)) {
            throw new IllegalArgumentException(
                    "database.sqlite.file must stay inside the plugin data folder");
        }

        Path current = dataFolder;
        for (Path segment : dataFolder.relativize(databaseFile)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                        "database.sqlite.file must not traverse symbolic links");
            }
        }

        Path parent = databaseFile.getParent();
        if (parent == null
                || !parent.startsWith(dataFolder)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(parent)) {
            throw new IllegalArgumentException(
                    "database.sqlite.file has no writable, confined parent directory");
        }

        if (Files.exists(databaseFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isWritable(databaseFile)) {
                throw new IllegalArgumentException(
                        "database.sqlite.file must name a writable regular file");
            }
            return databaseFile;
        }
        return databaseFile;
    }
}
