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
import java.util.Map;
import java.util.UUID;
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
                    .filter(path -> path.getFileName().toString().startsWith("audit-"))
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
    }
}
