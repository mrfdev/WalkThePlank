package com.mrfdev.walktheplank.export;

import com.mrfdev.walktheplank.database.ScoreEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates operator-requested, UUID-owned leaderboard exports without database access. */
public final class LeaderboardExportService {
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final Path exportDirectory;
    private final Path dataFolder;

    public LeaderboardExportService(Path pluginDataFolder) {
        this.dataFolder = Objects.requireNonNull(pluginDataFolder, "pluginDataFolder")
                .toAbsolutePath()
                .normalize();
        this.exportDirectory = dataFolder.resolve("exports");
    }

    public ExportResult export(List<ScoreEntry> entries, String category, Instant capturedAt)
            throws IOException {
        List<ScoreEntry> snapshot = List.copyOf(Objects.requireNonNull(entries, "entries"));
        String checkedCategory = validateCategory(category);
        Instant checkedCapturedAt = Objects.requireNonNull(capturedAt, "capturedAt");

        long unresolved = snapshot.stream().filter(entry -> entry.uuid().isEmpty()).count();
        if (unresolved > 0L) {
            throw new IllegalStateException(
                    "Refusing UUID-only export because " + unresolved + " score rows have unresolved identities");
        }

        if (Files.isSymbolicLink(dataFolder)
                || !Files.isDirectory(dataFolder, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(exportDirectory)) {
            throw new IOException("Export directory must not be a symbolic link");
        }
        Files.createDirectories(exportDirectory);
        if (Files.isSymbolicLink(exportDirectory)) {
            throw new IOException("Export directory must not be a symbolic link");
        }
        String stem = "walktheplank-" + checkedCategory + '-' + FILE_TIMESTAMP.format(checkedCapturedAt);
        Path csv = uniqueDestination(stem, ".csv");
        Path json = uniqueDestination(stem, ".json");
        writeAtomically(csv, encodeCsv(snapshot));
        try {
            writeAtomically(json, encodeJson(snapshot, checkedCategory, checkedCapturedAt));
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(csv);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return new ExportResult(csv.getFileName().toString(), json.getFileName().toString(), snapshot.size());
    }

    private Path uniqueDestination(String stem, String extension) {
        Path candidate = exportDirectory.resolve(stem + extension);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = exportDirectory.resolve(stem + '-' + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private static String encodeCsv(List<ScoreEntry> entries) {
        StringBuilder csv = new StringBuilder(64 + entries.size() * 96);
        csv.append("uuid,last_known_name,score,rank,updated_at\r\n");
        for (ScoreEntry entry : entries) {
            UUID uuid = entry.uuid().orElseThrow();
            appendCsv(csv, uuid.toString());
            csv.append(',');
            appendCsv(csv, entry.username());
            csv.append(',').append(entry.score());
            csv.append(',').append(entry.rank()).append(',');
            appendCsv(csv, entry.updatedAt().map(Instant::toString).orElse(""));
            csv.append("\r\n");
        }
        return csv.toString();
    }

    private static String encodeJson(List<ScoreEntry> entries, String category, Instant capturedAt) {
        StringBuilder json = new StringBuilder(128 + entries.size() * 128);
        json.append("{\n  \"schema\": 1,\n  \"category\": \"");
        appendJson(json, category);
        json.append("\",\n  \"captured_at\": \"");
        appendJson(json, capturedAt.toString());
        json.append("\",\n  \"entries\": [");
        for (int index = 0; index < entries.size(); index++) {
            ScoreEntry entry = entries.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("\n    {\"uuid\": \"");
            appendJson(json, entry.uuid().orElseThrow().toString());
            json.append("\", \"last_known_name\": \"");
            appendJson(json, entry.username());
            json.append("\", \"score\": ").append(entry.score());
            json.append(", \"rank\": ").append(entry.rank());
            json.append(", \"updated_at\": ");
            if (entry.updatedAt().isPresent()) {
                json.append('"');
                appendJson(json, entry.updatedAt().orElseThrow().toString());
                json.append('"');
            } else {
                json.append("null");
            }
            json.append('}');
        }
        if (!entries.isEmpty()) {
            json.append('\n');
        }
        return json.append("  ]\n}\n").toString();
    }

    private static void appendCsv(StringBuilder target, String value) {
        String safeValue = neutralizeSpreadsheetFormula(value);
        target.append('"').append(safeValue.replace("\"", "\"\"")).append('"');
    }

    private static String neutralizeSpreadsheetFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        boolean controlWhitespace = false;
        int candidate = 0;
        while (candidate < value.length()
                && Character.isWhitespace(value.charAt(candidate))) {
            char character = value.charAt(candidate++);
            controlWhitespace |= character == '\t' || character == '\r' || character == '\n';
        }
        if (controlWhitespace
                || candidate < value.length()
                        && "=+-@".indexOf(value.charAt(candidate)) >= 0) {
            return '\'' + value;
        }
        return value;
    }

    private static void appendJson(StringBuilder target, String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (character < 0x20) {
                        target.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        target.append(character);
                    }
                }
            }
        }
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        Path parent = Objects.requireNonNull(destination.getParent(), "destination parent");
        forceDirectory(parent);
        Path temporary = Files.createTempFile(parent, ".walktheplank-export-", ".tmp");
        boolean moved = false;
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Filesystem does not support atomic leaderboard exports", exception);
            }
            forceDirectory(parent);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static String validateCategory(String category) {
        Objects.requireNonNull(category, "category");
        String normalized = category.strip().toLowerCase(Locale.ROOT);
        if (!CATEGORY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("category must be a short lowercase identifier");
        }
        return normalized;
    }

    public record ExportResult(String csvFileName, String jsonFileName, int rows) {
        public ExportResult {
            Objects.requireNonNull(csvFileName, "csvFileName");
            Objects.requireNonNull(jsonFileName, "jsonFileName");
            if (rows < 0) {
                throw new IllegalArgumentException("rows cannot be negative");
            }
        }
    }
}
