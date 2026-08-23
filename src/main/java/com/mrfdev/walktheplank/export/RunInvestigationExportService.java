package com.mrfdev.walktheplank.export;

import com.mrfdev.walktheplank.build.BuildInfo;
import com.mrfdev.walktheplank.database.RunInvestigationQuery;
import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Writes one bounded, redacted retained-run investigation bundle without database access. */
public final class RunInvestigationExportService {
    public static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final Path dataFolder;
    private final Path exportDirectory;

    public RunInvestigationExportService(Path pluginDataFolder) {
        dataFolder = Objects.requireNonNull(pluginDataFolder, "pluginDataFolder")
                .toAbsolutePath()
                .normalize();
        exportDirectory = dataFolder.resolve("exports");
    }

    public ExportResult export(
            List<RunInvestigationRecord> runs,
            RunInvestigationQuery query,
            Instant capturedAt,
            BuildInfo generator) throws IOException {
        List<RunInvestigationRecord> snapshot = List.copyOf(
                Objects.requireNonNull(runs, "runs"));
        RunInvestigationQuery checkedQuery = Objects.requireNonNull(query, "query");
        Instant checkedCapturedAt = normalizeInstant(capturedAt, "capturedAt");
        BuildInfo checkedGenerator = Objects.requireNonNull(generator, "generator");
        validateEvidenceText(checkedGenerator.releaseLabel(), "generator release", 128);
        validateEvidenceText(checkedGenerator.artifactFile(), "generator artifact", 128);
        validateSnapshot(snapshot, checkedQuery);
        requireSafeDirectory();

        String stem = "walktheplank-investigation-"
                + FILE_TIMESTAMP.format(checkedCapturedAt);
        Path destination = uniqueDestination(stem);
        byte[] content = encodeJson(
                        snapshot, checkedQuery, checkedCapturedAt, checkedGenerator)
                .getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(content);
        writeAtomically(destination, content, sha256);
        return new ExportResult(
                destination.getFileName().toString(),
                snapshot.size(),
                SCHEMA_VERSION,
                sha256);
    }

    private void requireSafeDirectory() throws IOException {
        if (Files.isSymbolicLink(dataFolder)
                || !Files.isDirectory(dataFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin data directory is not a safe regular directory");
        }
        if (Files.exists(exportDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(exportDirectory)
                    || !Files.isDirectory(exportDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Export directory is not a safe regular directory");
            }
        } else {
            Files.createDirectory(exportDirectory);
        }
        if (Files.isSymbolicLink(exportDirectory)
                || !Files.isDirectory(exportDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Export directory is not a safe regular directory");
        }
        forceDirectory(dataFolder);
    }

    private Path uniqueDestination(String stem) throws IOException {
        Path candidate = exportDirectory.resolve(stem + ".json");
        for (int suffix = 1; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); suffix++) {
            if (suffix > 10_000) {
                throw new IOException("Could not allocate a unique investigation export name");
            }
            candidate = exportDirectory.resolve(stem + '-' + suffix + ".json");
        }
        return candidate;
    }

    private static void validateSnapshot(
            List<RunInvestigationRecord> runs,
            RunInvestigationQuery query) {
        if (runs.size() > query.limit()
                || runs.size() > RunInvestigationQuery.MAXIMUM_RESULTS) {
            throw new IllegalArgumentException(
                    "Investigation results exceed the immutable query bound");
        }
        Set<UUID> runIds = new HashSet<>();
        long previousSequence = 0L;
        boolean first = true;
        for (RunInvestigationRecord run : runs) {
            Objects.requireNonNull(run, "run");
            if (!first && run.sequence() >= previousSequence) {
                throw new IllegalArgumentException(
                        "Investigation results must be strictly newest-first");
            }
            if (!runIds.add(run.runId())) {
                throw new IllegalArgumentException(
                        "Investigation results contain a duplicate run UUID");
            }
            if (!matches(query, run)) {
                throw new IllegalArgumentException(
                        "Investigation result does not match its immutable query");
            }
            validateEvidenceText(run.arenaId(), "arenaId", 128);
            validateEvidenceText(run.release(), "release", 128);
            run.endReason().ifPresent(
                    value -> validateEvidenceText(value, "endReason", 64));
            previousSequence = run.sequence();
            first = false;
        }
        query.arenaId().ifPresent(value -> validateEvidenceText(value, "arenaId", 128));
        query.release().ifPresent(value -> validateEvidenceText(value, "release", 128));
    }

    private static boolean matches(
            RunInvestigationQuery query,
            RunInvestigationRecord run) {
        return query.status().map(value -> value == run.status()).orElse(true)
                && query.playerId().map(value -> value.equals(run.playerId())).orElse(true)
                && query.runId().map(value -> value.equals(run.runId())).orElse(true)
                && query.arenaId().map(value -> value.equals(run.arenaId())).orElse(true)
                && query.seasonId().map(value -> run.seasonId().filter(value::equals).isPresent())
                        .orElse(true)
                && query.release().map(value -> value.equals(run.release())).orElse(true)
                && query.startedAtInclusive()
                        .map(value -> !run.startedAt().isBefore(value))
                        .orElse(true)
                && query.startedBeforeExclusive()
                        .map(value -> run.startedAt().isBefore(value))
                        .orElse(true);
    }

    private static String encodeJson(
            List<RunInvestigationRecord> runs,
            RunInvestigationQuery query,
            Instant capturedAt,
            BuildInfo generator) {
        StringBuilder json = new StringBuilder(512 + runs.size() * 640);
        json.append("{\n  \"schema\": \"walktheplank.run-investigation\",\n")
                .append("  \"schema_version\": ").append(SCHEMA_VERSION).append(",\n")
                .append("  \"captured_at\": ");
        appendJsonString(json, capturedAt.toString());
        json.append(",\n  \"generator\": {\n")
                .append("    \"release\": ");
        appendJsonString(json, generator.releaseLabel());
        json.append(",\n    \"artifact\": ");
        appendJsonString(json, generator.artifactFile());
        json.append(",\n    \"source_commit\": ");
        appendJsonString(json, generator.sourceCommit());
        json.append(",\n    \"source_dirty\": ")
                .append(generator.sourceDirty()).append("\n  },\n")
                .append("  \"query\": {\n");
        appendOptional(json, "status", query.status().map(Enum::name), true);
        appendOptional(json, "player_uuid", query.playerId().map(UUID::toString), true);
        appendOptional(json, "run_uuid", query.runId().map(UUID::toString), true);
        appendOptional(
                json,
                "arena_id_sha256",
                query.arenaId().map(RunInvestigationExportService::sha256Text),
                true);
        appendOptional(json, "season_uuid", query.seasonId().map(UUID::toString), true);
        appendOptional(
                json,
                "release_sha256",
                query.release().map(RunInvestigationExportService::sha256Text),
                true);
        appendOptional(
                json,
                "started_at_inclusive",
                query.startedAtInclusive().map(Instant::toString),
                true);
        appendOptional(
                json,
                "started_before_exclusive",
                query.startedBeforeExclusive().map(Instant::toString),
                true);
        json.append("    \"limit\": ").append(query.limit()).append("\n  },\n")
                .append("  \"result_count\": ").append(runs.size()).append(",\n")
                .append("  \"newest_first\": true,\n")
                .append("  \"runs\": [");
        for (int index = 0; index < runs.size(); index++) {
            RunInvestigationRecord run = runs.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("\n    {\n")
                    .append("      \"sequence\": ").append(run.sequence()).append(",\n")
                    .append("      \"run_uuid\": ");
            appendJsonString(json, run.runId().toString());
            json.append(",\n      \"player_uuid\": ");
            appendJsonString(json, run.playerId().toString());
            json.append(",\n      \"arena_id\": ");
            appendJsonString(json, run.arenaId());
            json.append(",\n      \"started_at\": ");
            appendJsonString(json, run.startedAt().toString());
            json.append(",\n      \"ended_at\": ");
            appendOptionalValue(json, run.endedAt().map(Instant::toString));
            json.append(",\n      \"score\": ");
            if (run.score().isPresent()) {
                json.append(run.score().orElseThrow());
            } else {
                json.append("null");
            }
            json.append(",\n      \"end_reason\": ");
            appendOptionalValue(json, run.endReason());
            json.append(",\n      \"release\": ");
            appendJsonString(json, run.release());
            json.append(",\n      \"season_uuid\": ");
            appendOptionalValue(json, run.seasonId().map(UUID::toString));
            json.append(",\n      \"status\": ");
            appendJsonString(json, run.status().name());
            json.append(",\n      \"reward_plan\": ");
            if (run.rewardPlanId().isPresent()) {
                json.append("{\"plan_uuid\": ");
                appendJsonString(json, run.rewardPlanId().orElseThrow().toString());
                json.append(", \"status\": ");
                appendJsonString(json, run.rewardPlanStatus().orElseThrow().name());
                json.append('}');
            } else {
                json.append("null");
            }
            json.append("\n    }");
        }
        if (!runs.isEmpty()) {
            json.append('\n');
        }
        return json.append("  ]\n}\n").toString();
    }

    private static void appendOptional(
            StringBuilder target,
            String name,
            java.util.Optional<String> value,
            boolean comma) {
        target.append("    \"");
        appendJson(target, name);
        target.append("\": ");
        appendOptionalValue(target, value);
        target.append(comma ? ",\n" : "\n");
    }

    private static void appendOptionalValue(
            StringBuilder target,
            java.util.Optional<String> value) {
        if (value.isPresent()) {
            appendJsonString(target, value.orElseThrow());
        } else {
            target.append("null");
        }
    }

    private static void appendJsonString(StringBuilder target, String value) {
        target.append('"');
        appendJson(target, value);
        target.append('"');
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
                        target.append(String.format(
                                Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        target.append(character);
                    }
                }
            }
        }
    }

    private static void validateEvidenceText(
            String value,
            String name,
            int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()
                || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException(
                    "Investigation evidence contains invalid " + name);
        }
        String stripped = value.strip();
        if (stripped.startsWith("/")
                || stripped.startsWith("\\")
                || stripped.contains("~/")
                || stripped.contains("~\\")
                || stripped.matches("^[A-Za-z]:[\\\\/].*")
                || stripped.matches(".*\\s/\\S+.*")
                || stripped.matches(".*\\s[A-Za-z]:[\\\\/]\\S+.*")
                || stripped.contains(" \\\\")) {
            throw new IllegalArgumentException(
                    "Investigation evidence contains path-like " + name);
        }
    }

    private static Instant normalizeInstant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        try {
            return Instant.ofEpochMilli(value.toEpochMilli());
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(name + " is outside millisecond range", invalid);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String sha256Text(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(
            Path destination,
            byte[] content,
            String sha256) throws IOException {
        Path parent = Objects.requireNonNull(destination.getParent(), "destination parent");
        forceDirectory(parent);
        Path temporary = Files.createTempFile(
                parent, ".walktheplank-investigation-", ".tmp");
        boolean published = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.createLink(destination, temporary);
            } catch (FileAlreadyExistsException collision) {
                throw new IOException(
                        "Investigation export destination already exists",
                        collision);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException(
                        "Filesystem does not support no-replace atomic investigation exports",
                        unsupported);
            }
            published = true;
            try {
                Files.delete(temporary);
                forceDirectory(parent);
            } catch (IOException failure) {
                throw new RunInvestigationExportCommitUncertainException(
                        destination.getFileName().toString(), sha256, failure);
            }
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public record ExportResult(
            String fileName,
            int rows,
            int schemaVersion,
            String sha256) {
        public ExportResult {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(sha256, "sha256");
            if (fileName.contains("/") || fileName.contains("\\")) {
                throw new IllegalArgumentException("fileName must be a basename");
            }
            if (rows < 0 || rows > RunInvestigationQuery.MAXIMUM_RESULTS) {
                throw new IllegalArgumentException("rows are outside the investigation bound");
            }
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported investigation export schema");
            }
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be a lowercase digest");
            }
        }
    }
}
