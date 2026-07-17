package com.mrfdev.walktheplank.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrfdev.walktheplank.database.ScoreEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeaderboardExportServiceTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-07-14T12:34:56Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyWritesUuidOwnedCsvAndJson() throws Exception {
        UUID firstId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID secondId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        List<ScoreEntry> entries = List.of(
                new ScoreEntry(1L, Optional.of(firstId), "Player,One", 42, 1, Optional.of(CAPTURED_AT)),
                new ScoreEntry(2L, Optional.of(secondId), "Quoted\"Name", 30, 2, Optional.empty()));
        LeaderboardExportService service = new LeaderboardExportService(temporaryDirectory);

        LeaderboardExportService.ExportResult result = service.export(entries, "all_time", CAPTURED_AT);

        assertEquals(2, result.rows());
        String csv = Files.readString(temporaryDirectory.resolve("exports").resolve(result.csvFileName()));
        String json = Files.readString(temporaryDirectory.resolve("exports").resolve(result.jsonFileName()));
        assertTrue(csv.contains("\"Player,One\""));
        assertTrue(csv.contains("\"Quoted\"\"Name\""));
        assertTrue(json.contains(firstId.toString()));
        assertTrue(json.contains("\"category\": \"all_time\""));
        try (var paths = Files.list(temporaryDirectory.resolve("exports"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void neutralizesSpreadsheetFormulaPrefixesInCsvButKeepsJsonRaw() throws Exception {
        List<ScoreEntry> entries = List.of(
                entry(1L, "=1+1"),
                entry(2L, "+SUM(A1:A2)"),
                entry(3L, "  -42+7"),
                entry(4L, "\t@SUM(A1:A2)"));
        LeaderboardExportService service = new LeaderboardExportService(temporaryDirectory);

        LeaderboardExportService.ExportResult result =
                service.export(entries, "formula_test", CAPTURED_AT);

        String csv = Files.readString(
                temporaryDirectory.resolve("exports").resolve(result.csvFileName()));
        String json = Files.readString(
                temporaryDirectory.resolve("exports").resolve(result.jsonFileName()));
        assertTrue(csv.contains("\"'=1+1\""));
        assertTrue(csv.contains("\"'+SUM(A1:A2)\""));
        assertTrue(csv.contains("\"'  -42+7\""));
        assertTrue(csv.contains("\"'\t@SUM(A1:A2)\""));
        assertTrue(json.contains("\"last_known_name\": \"=1+1\""));
        assertTrue(json.contains("\"last_known_name\": \"+SUM(A1:A2)\""));
        assertFalse(json.contains("\"last_known_name\": \"'=1+1\""));
    }

    @Test
    void refusesRowsWithoutResolvedUuid() {
        ScoreEntry unresolved = new ScoreEntry(
                1L, Optional.empty(), "LegacyName", 10, 1, Optional.empty());
        LeaderboardExportService service = new LeaderboardExportService(temporaryDirectory);

        assertThrows(
                IllegalStateException.class,
                () -> service.export(List.of(unresolved), "all_time", CAPTURED_AT));
    }

    @Test
    void rejectsUnsafeCategoryNames() {
        LeaderboardExportService service = new LeaderboardExportService(temporaryDirectory);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(List.of(), "../secrets", CAPTURED_AT));
    }

    private static ScoreEntry entry(long id, String username) {
        return new ScoreEntry(
                id,
                Optional.of(new UUID(0L, id)),
                username,
                10,
                Math.toIntExact(id),
                Optional.empty());
    }
}
