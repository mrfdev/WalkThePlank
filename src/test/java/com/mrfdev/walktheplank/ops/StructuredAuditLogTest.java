package com.mrfdev.walktheplank.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredAuditLogTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T12:34:56.789Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesBoundedStructuredJsonWithoutControlCharacters() throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.1.0 / build 003", 4_096, 3, FIXED_CLOCK);
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        log.record("session.end", playerId, "summer\nmain", Map.of(
                "score", 42,
                "reason", "LEAVE",
                "note", "safe \"value\""));

        String line = Files.readString(log.currentFile());
        assertTrue(line.endsWith("\n"));
        assertTrue(line.contains("\"event\":\"session.end\""));
        assertTrue(line.contains(playerId.toString()));
        assertTrue(line.contains("\"arena\":\"summer main\""));
        assertTrue(line.contains("safe \\\"value\\\""));
        assertFalse(line.contains("summer\nmain"));
    }

    @Test
    void rotatesAndPrunesArchives() throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.1.0 / build 003", 256, 2, FIXED_CLOCK);

        for (int index = 0; index < 8; index++) {
            log.record("admin.validate", null, null, Map.of(
                    "valid", true,
                    "summary", "x".repeat(100),
                    "sequence", index));
        }

        try (var paths = Files.list(temporaryDirectory.resolve("audit"))) {
            long archiveCount = paths
                    .filter(path -> path.getFileName().toString()
                            .startsWith("audit-chain-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .count();
            assertEquals(2, archiveCount);
        }
        assertTrue(Files.exists(log.currentFile()));
    }

    @Test
    void rejectsSensitiveAndNestedFields() throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.1.0 / build 003", 4_096, 3, FIXED_CLOCK);

        assertThrows(IllegalArgumentException.class,
                () -> log.record("admin.action", null, null, Map.of("raw_command", "op somebody")));
        assertThrows(IllegalArgumentException.class,
                () -> log.record("admin.action", null, null, Map.of("nested", Map.of("x", "y"))));
    }

    @Test
    void rejectsAnUnsafeCurrentAuditTargetDuringOpen() throws Exception {
        Path auditDirectory = Files.createDirectories(temporaryDirectory.resolve("audit"));
        Files.createDirectory(auditDirectory.resolve("audit.jsonl"));

        assertThrows(IOException.class, () -> StructuredAuditLog.open(
                temporaryDirectory, "v2.1.0 / build 003", 4_096, 3, FIXED_CLOCK));
    }

    @Test
    void refusesAReplacementSymlinkWithoutWritingItsTarget() throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.1.0 / build 003", 4_096, 3, FIXED_CLOCK);
        Path target = temporaryDirectory.resolve("outside.jsonl");
        Files.writeString(target, "unchanged\n");
        Files.delete(log.currentFile());
        Files.createSymbolicLink(log.currentFile(), target);

        assertThrows(IOException.class,
                () -> log.record("admin.action", null, null, Map.of("valid", true)));
        assertEquals("unchanged\n", Files.readString(target));
        assertFalse(log.health().writerHealthy());
    }

    @Test
    void verifiesChainAcrossRestartAndRejectsSameLengthTampering() throws Exception {
        StructuredAuditLog first = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 4_096, 3, FIXED_CLOCK);
        first.record("security.movement_anomaly", null, "main", Map.of(
                "kind", "riptide",
                "action", "blocked"));

        String original = Files.readString(first.currentFile());
        assertTrue(original.contains("\"audit_sequence\":1"));
        assertTrue(original.contains("\"previous_hash\":\"" + "0".repeat(64) + "\""));
        assertTrue(original.matches("(?s).*\"record_hash\":\"[0-9a-f]{64}\"}\\n"));

        StructuredAuditLog reopened = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 4_096, 3, FIXED_CLOCK);
        reopened.record("admin.validate", null, null, Map.of("valid", true));
        assertTrue(Files.readString(reopened.currentFile())
                .contains("\"audit_sequence\":2"));

        String tampered = Files.readString(reopened.currentFile())
                .replace("\"valid\":true", "\"valid\":fals");
        assertEquals(
                Files.size(reopened.currentFile()),
                tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        Files.writeString(reopened.currentFile(), tampered);
        assertThrows(IOException.class, () -> StructuredAuditLog.open(
                temporaryDirectory,
                "v2.4.0 / build 008",
                4_096,
                3,
                FIXED_CLOCK));
    }

    @Test
    void detectsTruncationAgainstDurableState() throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 4_096, 3, FIXED_CLOCK);
        log.record("admin.validate", null, null, Map.of("valid", true));
        Files.writeString(log.currentFile(), "");

        assertThrows(IOException.class, () -> StructuredAuditLog.open(
                temporaryDirectory,
                "v2.4.0 / build 008",
                4_096,
                3,
                FIXED_CLOCK));
    }

    @Test
    void rejectsDeletionOfOnlyTheAuditStateCheckpoint() throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 4_096, 3, FIXED_CLOCK);
        log.record("admin.validate", null, null, Map.of("valid", true));
        Files.delete(temporaryDirectory.resolve("audit/audit-state.properties"));

        assertThrows(IOException.class, () -> StructuredAuditLog.open(
                temporaryDirectory,
                "v2.4.0 / build 008",
                4_096,
                3,
                FIXED_CLOCK));
    }

    @Test
    void rejectsReclassificationAfterBothCheckpointsAreDeleted() throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 4_096, 3, FIXED_CLOCK);
        log.record("admin.validate", null, null, Map.of("valid", true));
        Files.delete(temporaryDirectory.resolve("audit/audit-state.properties"));
        Files.delete(temporaryDirectory.resolve("audit/audit-anchor.properties"));

        assertThrows(IOException.class, () -> StructuredAuditLog.open(
                temporaryDirectory,
                "v2.4.0 / build 008",
                4_096,
                3,
                FIXED_CLOCK));
    }

    @Test
    void recoversAFirstStartInterruptedAfterTheInitialAnchorCommit()
            throws Exception {
        Path auditDirectory = Files.createDirectories(temporaryDirectory.resolve("audit"));
        Files.writeString(auditDirectory.resolve("audit.jsonl"), "");
        UUID chainId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        Files.writeString(
                auditDirectory.resolve("audit-anchor.properties"),
                "chain=" + chainId + '\n'
                        + "sequence=0\n"
                        + "hash=" + "0".repeat(64) + '\n');

        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 4_096, 3, FIXED_CLOCK);
        log.record("plugin.enable", null, null, Map.of("scores", 0));

        assertTrue(Files.isRegularFile(
                auditDirectory.resolve("audit-state.properties")));
        assertTrue(Files.readString(log.currentFile())
                .contains("\"audit_chain\":\"" + chainId + '"'));
        assertEquals(1L, log.health().sequence());
    }

    @Test
    void preservesUnchainedLegacyLogAndStartsANewVerifiedChain() throws Exception {
        Path auditDirectory = Files.createDirectories(temporaryDirectory.resolve("audit"));
        Files.writeString(
                auditDirectory.resolve("audit.jsonl"),
                "{\"event\":\"legacy\"}\n");

        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 4_096, 3, FIXED_CLOCK);
        log.record("plugin.enable", null, null, Map.of("scores", 1));

        try (var paths = Files.list(auditDirectory)) {
            assertEquals(1L, paths
                    .filter(path -> path.getFileName().toString()
                            .startsWith("audit-legacy-"))
                    .count());
        }
        assertTrue(Files.readString(log.currentFile())
                .contains("\"audit_sequence\":1"));
    }

    @Test
    void restoresAnArchiveWhenPruningWasInterruptedBeforeAnchorCommit()
            throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 256, 3, FIXED_CLOCK);
        log.record("admin.validate", null, null, Map.of("sequence", 1));
        log.record("admin.validate", null, null, Map.of("sequence", 2));
        Path archive = onlyArchive();
        Path pending = archive.resolveSibling(archive.getFileName() + ".pruning");
        Files.move(archive, pending);

        StructuredAuditLog reopened = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 256, 3, FIXED_CLOCK);

        assertTrue(Files.isRegularFile(archive));
        assertFalse(Files.exists(pending));
        assertEquals(2L, reopened.health().sequence());
    }

    @Test
    void finishesArchiveDeletionWhenPruningWasInterruptedAfterAnchorCommit()
            throws Exception {
        StructuredAuditLog log = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 256, 3, FIXED_CLOCK);
        log.record("admin.validate", null, null, Map.of("sequence", 1));
        log.record("admin.validate", null, null, Map.of("sequence", 2));
        Path archive = onlyArchive();
        Path pending = archive.resolveSibling(archive.getFileName() + ".pruning");
        Files.move(archive, pending);

        String line = Files.readAllLines(pending).getLast();
        Matcher metadata = Pattern.compile(
                        ".*\"audit_chain\":\"([0-9a-f-]+)\","
                                + "\"audit_sequence\":([0-9]+),"
                                + "\"previous_hash\":\"[0-9a-f]{64}\","
                                + "\"record_hash\":\"([0-9a-f]{64})\"}$")
                .matcher(line);
        assertTrue(metadata.matches());
        Files.writeString(
                temporaryDirectory.resolve("audit/audit-anchor.properties"),
                "chain=" + metadata.group(1) + '\n'
                        + "sequence=" + metadata.group(2) + '\n'
                        + "hash=" + metadata.group(3) + '\n');

        StructuredAuditLog reopened = StructuredAuditLog.open(
                temporaryDirectory, "v2.4.0 / build 008", 256, 3, FIXED_CLOCK);

        assertFalse(Files.exists(archive));
        assertFalse(Files.exists(pending));
        assertEquals(2L, reopened.health().sequence());
        assertEquals(1L, reopened.health().anchorSequence());
    }

    private Path onlyArchive() throws IOException {
        try (var paths = Files.list(temporaryDirectory.resolve("audit"))) {
            List<Path> archives = paths
                    .filter(path -> path.getFileName().toString()
                            .matches("audit-chain-.*\\.jsonl"))
                    .toList();
            assertEquals(1, archives.size());
            return archives.getFirst();
        }
    }
}
