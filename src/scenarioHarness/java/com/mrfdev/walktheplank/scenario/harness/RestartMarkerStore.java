package com.mrfdev.walktheplank.scenario.harness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable, content-free marker used to prove a second Paper start. */
final class RestartMarkerStore {
    private static final int MAX_MARKER_BYTES = 1_024;
    private final Path marker;

    RestartMarkerStore(Path dataFolder) {
        marker = dataFolder.resolve("restart.marker");
    }

    Marker prepare(String targetVersion) throws IOException {
        String safeVersion = requireSafeVersion(targetVersion);
        UUID nonce = UUID.randomUUID();
        byte[] bytes = String.join(
                        "\n",
                        "schema=1",
                        "target-version=" + safeVersion,
                        "nonce=" + nonce,
                        "")
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MARKER_BYTES) {
            throw new IOException("Restart marker exceeded its size limit");
        }

        Path directory = marker.getParent();
        Files.createDirectories(directory);
        Path temporary = directory.resolve("restart.marker.tmp-" + UUID.randomUUID());
        try {
            writeAndForce(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        marker,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Restart marker directory does not support atomic moves", exception);
            }
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Marker(safeVersion, nonce);
    }

    Optional<Marker> consume() throws IOException {
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.size(marker) > MAX_MARKER_BYTES) {
            throw new IOException("Restart marker is not a bounded regular file");
        }

        List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
        if (lines.size() != 3
                || !"schema=1".equals(lines.get(0))
                || !lines.get(1).startsWith("target-version=")
                || !lines.get(2).startsWith("nonce=")) {
            throw new IOException("Restart marker has an invalid schema");
        }
        String version = requireSafeVersion(lines.get(1).substring("target-version=".length()));
        UUID nonce;
        try {
            nonce = UUID.fromString(lines.get(2).substring("nonce=".length()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Restart marker nonce is invalid", exception);
        }
        Files.delete(marker);
        forceDirectory(marker.getParent());
        return Optional.of(new Marker(version, nonce));
    }

    void clear() throws IOException {
        if (Files.deleteIfExists(marker)) {
            forceDirectory(marker.getParent());
        }
    }

    boolean exists() {
        return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeAndForce(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The marker file itself was forced; directory fsync is not portable to every platform.
        }
    }

    private static String requireSafeVersion(String version) throws IOException {
        if (version == null
                || version.isBlank()
                || version.length() > 96
                || !version.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Target version is unsafe for a restart marker");
        }
        return version;
    }

    record Marker(String targetVersion, UUID nonce) {
    }
}
