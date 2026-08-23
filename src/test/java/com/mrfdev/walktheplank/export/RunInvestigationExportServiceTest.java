package com.mrfdev.walktheplank.export;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrfdev.walktheplank.build.BuildInfo;
import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RunInvestigationQuery;
import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import com.mrfdev.walktheplank.database.RunStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunInvestigationExportServiceTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-23T12:34:56.789Z");
    private static final UUID PLAYER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ONE = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID RUN_TWO = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final UUID PLAN = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");
    private static final BuildInfo GENERATOR = new BuildInfo(
            "2.9.0",
            "030",
            "1MB-WalkThePlank-v2.9.0-030-j25-26.2.jar",
            "25",
            "26.2",
            "26.2.build.84-stable",
            84,
            "2.12.3",
            "a".repeat(40),
            false);

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOneAtomicRedactedJsonBundleWithExactDigest() throws Exception {
        RunInvestigationExportService service =
                new RunInvestigationExportService(temporaryDirectory);
        List<RunInvestigationRecord> runs = List.of(completed(), aborted());

        RunInvestigationExportService.ExportResult result = service.export(
                runs,
                RunInvestigationQuery.all(20),
                CAPTURED_AT,
                GENERATOR);

        Path destination = temporaryDirectory.resolve("exports").resolve(result.fileName());
        byte[] bytes = Files.readAllBytes(destination);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals(2, result.rows()),
                () -> assertEquals(1, result.schemaVersion()),
                () -> assertEquals(sha256(bytes), result.sha256()),
                () -> assertTrue(json.contains(
                        "\"schema\": \"walktheplank.run-investigation\"")),
                () -> assertTrue(json.contains("\"result_count\": 2")),
                () -> assertTrue(json.contains("\"newest_first\": true")),
                () -> assertTrue(json.contains(RUN_ONE.toString())),
                () -> assertTrue(json.contains(PLAN.toString())),
                () -> assertTrue(json.contains("main-\\\"quoted\\\"\\\\arena")),
                () -> assertTrue(json.contains("\"source_dirty\": false")),
                () -> assertFalse(json.contains("Alice")),
                () -> assertFalse(json.contains("username")),
                () -> assertFalse(json.contains("raw_command")),
                () -> assertFalse(json.contains(temporaryDirectory.toString())));
        try (var paths = Files.list(temporaryDirectory.resolve("exports"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void neverOverwritesAnExistingBundleName() throws Exception {
        RunInvestigationExportService service =
                new RunInvestigationExportService(temporaryDirectory);
        RunInvestigationQuery query = RunInvestigationQuery.all(20);

        RunInvestigationExportService.ExportResult first = service.export(
                List.of(completed()), query, CAPTURED_AT, GENERATOR);
        Path firstPath = temporaryDirectory.resolve("exports").resolve(first.fileName());
        byte[] firstBytes = Files.readAllBytes(firstPath);
        RunInvestigationExportService.ExportResult second = service.export(
                List.of(completed()), query, CAPTURED_AT, GENERATOR);

        assertNotEquals(first.fileName(), second.fileName());
        assertTrue(Files.isRegularFile(
                temporaryDirectory.resolve("exports").resolve(first.fileName())));
        assertTrue(Files.isRegularFile(
                temporaryDirectory.resolve("exports").resolve(second.fileName())));
        assertEquals(HexFormat.of().formatHex(firstBytes),
                HexFormat.of().formatHex(Files.readAllBytes(firstPath)));
    }

    @Test
    void hashesFreeFormQuerySelectorsInsteadOfCopyingThem() throws Exception {
        RunInvestigationExportService service =
                new RunInvestigationExportService(temporaryDirectory);
        String operatorSupplied = "password=do-not-copy";

        RunInvestigationExportService.ExportResult result = service.export(
                List.of(),
                RunInvestigationQuery.forRelease(operatorSupplied, 20),
                CAPTURED_AT,
                GENERATOR);

        String json = Files.readString(
                temporaryDirectory.resolve("exports").resolve(result.fileName()));
        assertAll(
                () -> assertFalse(json.contains(operatorSupplied)),
                () -> assertTrue(json.contains("\"release_sha256\": \""
                        + sha256(operatorSupplied.getBytes(StandardCharsets.UTF_8)) + "\"")),
                () -> assertTrue(json.contains("\"arena_id_sha256\": null")));
    }

    @Test
    void rejectsUnboundedMismatchedDuplicateOrMisorderedSnapshots() {
        RunInvestigationExportService service =
                new RunInvestigationExportService(temporaryDirectory);
        RunInvestigationRecord completed = completed();
        RunInvestigationRecord aborted = aborted();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(
                                List.of(completed, aborted),
                                RunInvestigationQuery.all(1),
                                CAPTURED_AT,
                                GENERATOR)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(
                                List.of(completed),
                                RunInvestigationQuery.forPlayer(UUID.randomUUID(), 20),
                                CAPTURED_AT,
                                GENERATOR)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(
                                List.of(aborted, completed),
                                RunInvestigationQuery.all(20),
                                CAPTURED_AT,
                                GENERATOR)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(
                                List.of(completed, completed),
                                RunInvestigationQuery.all(20),
                                CAPTURED_AT,
                                GENERATOR)));
    }

    @Test
    void rejectsPathLikeEvidenceAndSymlinkedExportDirectory() throws Exception {
        RunInvestigationExportService service =
                new RunInvestigationExportService(temporaryDirectory);
        RunInvestigationRecord pathLike = new RunInvestigationRecord(
                3L,
                UUID.randomUUID(),
                PLAYER,
                "/private/arena",
                CAPTURED_AT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "release",
                Optional.empty(),
                RunStatus.STARTED,
                Optional.empty(),
                Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(
                        List.of(pathLike),
                        RunInvestigationQuery.all(20),
                        CAPTURED_AT,
                        GENERATOR));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(
                        List.of(),
                        RunInvestigationQuery.forRelease("/private/release", 20),
                        CAPTURED_AT,
                        GENERATOR));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(
                        List.of(),
                        RunInvestigationQuery.forRelease("error at ~/private/release", 20),
                        CAPTURED_AT,
                        GENERATOR));

        Path linkTarget = Files.createDirectory(temporaryDirectory.resolve("elsewhere"));
        Files.createSymbolicLink(temporaryDirectory.resolve("exports"), linkTarget);
        assertThrows(
                IOException.class,
                () -> service.export(
                        List.of(completed()),
                        RunInvestigationQuery.all(20),
                        CAPTURED_AT,
                        GENERATOR));
    }

    @Test
    void rejectsMalformedUnicodeEvidence() {
        RunInvestigationExportService service =
                new RunInvestigationExportService(temporaryDirectory);
        RunInvestigationRecord malformed = new RunInvestigationRecord(
                3L,
                UUID.randomUUID(),
                PLAYER,
                "arena-\uD800",
                CAPTURED_AT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "release",
                Optional.empty(),
                RunStatus.STARTED,
                Optional.empty(),
                Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(
                        List.of(malformed),
                        RunInvestigationQuery.all(20),
                        CAPTURED_AT,
                        GENERATOR));
    }

    private static RunInvestigationRecord completed() {
        return new RunInvestigationRecord(
                2L,
                RUN_ONE,
                PLAYER,
                "main-\"quoted\"\\arena",
                CAPTURED_AT.minusSeconds(120),
                Optional.of(CAPTURED_AT.minusSeconds(60)),
                Optional.of(42),
                Optional.of("FELL"),
                "v2.9.0 build 030 / 1MB-WalkThePlank-v2.9.0-030-j25-26.2.jar",
                Optional.empty(),
                RunStatus.COMPLETED,
                Optional.of(PLAN),
                Optional.of(RewardPlanStatus.PENDING));
    }

    private static RunInvestigationRecord aborted() {
        return new RunInvestigationRecord(
                1L,
                RUN_TWO,
                PLAYER,
                "secondary",
                CAPTURED_AT.minusSeconds(240),
                Optional.of(CAPTURED_AT.minusSeconds(180)),
                Optional.empty(),
                Optional.of("ADMIN"),
                "v2.8.4 build 029 / prior.jar",
                Optional.empty(),
                RunStatus.ABORTED,
                Optional.empty(),
                Optional.empty());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
