package com.mrfdev.walktheplank.ops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Small durable JSONL audit trail for administrative and lifecycle events.
 *
 * <p>Values are intentionally restricted to short scalars. Raw commands, filesystem paths,
 * credentials, chat, and arbitrary nested data do not belong in this log.</p>
 */
public final class StructuredAuditLog {
    private static final long DEFAULT_MAXIMUM_BYTES = 10L * 1024L * 1024L;
    private static final int DEFAULT_RETAINED_FILES = 10;
    private static final int MAXIMUM_FIELDS = 32;
    private static final int MAXIMUM_VALUE_LENGTH = 256;
    private static final Pattern EVENT_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,47}");
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "raw_command",
            "command_text",
            "command_args",
            "password",
            "secret",
            "token",
            "filesystem",
            "path",
            "chat");

    private final Path directory;
    private final Path currentFile;
    private final String release;
    private final long maximumBytes;
    private final int retainedFiles;
    private final Clock clock;

    public static StructuredAuditLog open(Path dataFolder, String release) throws IOException {
        return open(dataFolder, release, DEFAULT_MAXIMUM_BYTES, DEFAULT_RETAINED_FILES, Clock.systemUTC());
    }

    static StructuredAuditLog open(
            Path dataFolder,
            String release,
            long maximumBytes,
            int retainedFiles,
            Clock clock) throws IOException {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(release, "release");
        if (release.isBlank() || release.length() > 128) {
            throw new IllegalArgumentException("release must contain between 1 and 128 characters");
        }
        if (maximumBytes < 256) {
            throw new IllegalArgumentException("maximumBytes must be at least 256");
        }
        if (retainedFiles < 1 || retainedFiles > 100) {
            throw new IllegalArgumentException("retainedFiles must be between 1 and 100");
        }

        Path directory = dataFolder.resolve("audit");
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Audit directory must not be a symbolic link");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Audit directory must not be a symbolic link");
        }
        preflightCurrentFile(directory.resolve("audit.jsonl"));
        return new StructuredAuditLog(directory, release, maximumBytes, retainedFiles, clock);
    }

    private static void preflightCurrentFile(Path currentFile) throws IOException {
        if (Files.exists(currentFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(currentFile)
                        || !Files.isRegularFile(
                                currentFile, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Audit file must be a safe regular file");
        }
        try (FileChannel channel = FileChannel.open(
                currentFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            channel.force(false);
        }
    }

    private StructuredAuditLog(
            Path directory,
            String release,
            long maximumBytes,
            int retainedFiles,
            Clock clock) {
        this.directory = directory;
        this.currentFile = directory.resolve("audit.jsonl");
        this.release = normalize(release, 128);
        this.maximumBytes = maximumBytes;
        this.retainedFiles = retainedFiles;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void record(
            String event,
            UUID playerId,
            String arenaId,
            Map<String, ?> fields) throws IOException {
        validateEvent(event);
        Objects.requireNonNull(fields, "fields");
        if (fields.size() > MAXIMUM_FIELDS) {
            throw new IllegalArgumentException("audit event contains more than " + MAXIMUM_FIELDS + " fields");
        }

        Instant occurredAt = clock.instant();
        byte[] line = encode(occurredAt, event, playerId, arenaId, fields).getBytes(StandardCharsets.UTF_8);
        rotateBefore(line.length, occurredAt);
        try (FileChannel channel = FileChannel.open(
                currentFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
    }

    public Path currentFile() {
        return currentFile;
    }

    private String encode(
            Instant occurredAt,
            String event,
            UUID playerId,
            String arenaId,
            Map<String, ?> fields) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendStringProperty(json, "timestamp", occurredAt.toString(), false);
        appendStringProperty(json, "event", event, true);
        appendStringProperty(json, "release", release, true);
        if (playerId != null) {
            appendStringProperty(json, "player_uuid", playerId.toString(), true);
        }
        if (arenaId != null) {
            appendStringProperty(json, "arena", normalize(arenaId, 64), true);
        }

        for (Map.Entry<String, ?> entry : new TreeMap<>(fields).entrySet()) {
            String key = entry.getKey();
            validateKey(key);
            appendScalarProperty(json, key, entry.getValue());
        }
        return json.append("}\n").toString();
    }

    private void rotateBefore(int incomingBytes, Instant occurredAt) throws IOException {
        long currentBytes = safeCurrentFileSize();
        if (currentBytes == 0L) {
            return;
        }
        if (currentBytes <= maximumBytes - incomingBytes) {
            return;
        }

        String timestamp = ARCHIVE_TIMESTAMP.format(occurredAt);
        Path destination = uniqueArchivePath(timestamp);
        try {
            Files.move(currentFile, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(currentFile, destination);
        }
        pruneArchives();
    }

    private long safeCurrentFileSize() throws IOException {
        if (!Files.exists(currentFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                currentFile,
                BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Audit file must be a safe regular file");
        }
        return attributes.size();
    }

    private Path uniqueArchivePath(String timestamp) {
        Path candidate = directory.resolve("audit-" + timestamp + ".jsonl");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve("audit-" + timestamp + '-' + suffix + ".jsonl");
            suffix++;
        }
        return candidate;
    }

    private void pruneArchives() throws IOException {
        List<Path> archives = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("audit-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .forEach(archives::add);
        }
        archives.sort(Comparator.comparing(path -> path.getFileName().toString()));
        while (archives.size() > retainedFiles) {
            Files.deleteIfExists(archives.removeFirst());
        }
    }

    private static void validateEvent(String event) {
        Objects.requireNonNull(event, "event");
        if (!EVENT_PATTERN.matcher(event).matches()) {
            throw new IllegalArgumentException("invalid audit event name: " + event);
        }
    }

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "audit field key");
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid audit field key: " + key);
        }
        for (String sensitivePart : SENSITIVE_KEY_PARTS) {
            if (key.contains(sensitivePart)) {
                throw new IllegalArgumentException("sensitive audit field is not allowed: " + key);
            }
        }
    }

    private static void appendStringProperty(
            StringBuilder json,
            String key,
            String value,
            boolean prependComma) {
        if (prependComma) {
            json.append(',');
        }
        json.append('"').append(key).append("\":\"");
        appendEscaped(json, value);
        json.append('"');
    }

    private static void appendScalarProperty(StringBuilder json, String key, Object value) {
        json.append(',').append('"').append(key).append("\":");
        if (value == null) {
            json.append("null");
            return;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            json.append(value);
            return;
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            json.append(number);
            return;
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            json.append(number);
            return;
        }
        if (value instanceof Number) {
            throw new IllegalArgumentException("audit numeric values must be finite primitive numbers");
        }
        if (value instanceof UUID || value instanceof Enum<?> || value instanceof CharSequence) {
            json.append('"');
            appendEscaped(json, normalize(value.toString(), MAXIMUM_VALUE_LENGTH));
            json.append('"');
            return;
        }
        throw new IllegalArgumentException(
                "audit values must be scalar strings, UUIDs, enums, booleans, or numbers");
    }

    private static String normalize(String value, int maximumLength) {
        String normalized = value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .strip();
        if (normalized.length() <= maximumLength) {
            return normalized;
        }
        return normalized.substring(0, maximumLength - 1) + "\u2026";
    }

    private static void appendEscaped(StringBuilder json, String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
    }
}
