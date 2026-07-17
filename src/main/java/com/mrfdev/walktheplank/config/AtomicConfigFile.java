package com.mrfdev.walktheplank.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/** Same-directory temp/write/fsync/move persistence with a restorable last-known file. */
final class AtomicConfigFile {
    private final Path target;
    private final Path backup;

    AtomicConfigFile(Path target) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(this.target.getParent(), "target parent");
        this.backup = parent.resolve(this.target.getFileName() + ".backup");
    }

    CommitToken replaceWithBackup(byte[] expectedContents, String contents) throws IOException {
        byte[] expected = Objects.requireNonNull(expectedContents, "expectedContents").clone();
        Objects.requireNonNull(contents, "contents");
        byte[] committed = contents.getBytes(StandardCharsets.UTF_8);
        requireSafePaths();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The active configuration file does not exist");
        }
        requireUnchanged(expected);
        Path parent = Objects.requireNonNull(target.getParent(), "target parent");
        // Detect platforms/filesystems that cannot sync a directory before any candidate can be
        // committed, so a portability failure is never misreported as "config not replaced".
        forceDirectory(parent);
        Path candidateTemp = Files.createTempFile(parent, ".walktheplank-config-", ".tmp");
        Path backupTemp = Files.createTempFile(parent, ".walktheplank-backup-", ".tmp");
        try {
            Files.write(
                    candidateTemp,
                    committed,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            force(candidateTemp);
            Files.write(
                    backupTemp,
                    expected,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            force(backupTemp);
            move(backupTemp, backup);
            forceDirectory(parent);
            requireUnchanged(expected);
            move(candidateTemp, target);
            forceDirectory(parent);
            return new CommitToken(committed, expected);
        } finally {
            Files.deleteIfExists(candidateTemp);
            Files.deleteIfExists(backupTemp);
        }
    }

    void restoreBackup(CommitToken commitToken) throws IOException {
        CommitToken committed = Objects.requireNonNull(commitToken, "commitToken");
        requireSafePaths();
        requireUnchanged(
                committed.committedContents(),
                "config.yml changed after the arena edit was committed; refusing to overwrite the newer file");
        Path parent = Objects.requireNonNull(target.getParent(), "target parent");
        Path restoreTemp = Files.createTempFile(parent, ".walktheplank-restore-", ".tmp");
        try {
            Files.write(
                    restoreTemp,
                    committed.backupContents(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            force(restoreTemp);
            move(restoreTemp, target);
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(restoreTemp);
        }
    }

    void requireCommittedUnchanged(CommitToken commitToken) throws IOException {
        CommitToken committed = Objects.requireNonNull(commitToken, "commitToken");
        requireSafePaths();
        requireUnchanged(
                committed.committedContents(),
                "config.yml changed after the arena edit was committed");
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Filesystem does not support atomic configuration replacement", exception);
        }
    }

    private void requireSafePaths() throws IOException {
        Path parent = Objects.requireNonNull(target.getParent(), "target parent");
        if (Files.isSymbolicLink(parent)
                || Files.isSymbolicLink(target)
                || Files.isSymbolicLink(backup)) {
            throw new IOException("Configuration and backup paths must not be symbolic links");
        }
    }

    private void requireUnchanged(byte[] expected) throws IOException {
        requireUnchanged(
                expected,
                "config.yml changed while the arena edit was being validated; retry the command");
    }

    private void requireUnchanged(byte[] expected, String failureMessage) throws IOException {
        byte[] current = Files.readAllBytes(target);
        if (!Arrays.equals(expected, current)) {
            throw new IOException(failureMessage);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    record CommitToken(byte[] committedContents, byte[] backupContents) {
        CommitToken {
            committedContents = Objects.requireNonNull(committedContents, "committedContents").clone();
            backupContents = Objects.requireNonNull(backupContents, "backupContents").clone();
        }

        @Override
        public byte[] committedContents() {
            return committedContents.clone();
        }

        @Override
        public byte[] backupContents() {
            return backupContents.clone();
        }
    }
}
