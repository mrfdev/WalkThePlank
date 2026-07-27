package com.mrfdev.walktheplank.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcScoreRepositoryTest {
    private static final UUID ALICE_UUID =
            UUID.fromString("61219285-43f3-4693-869d-45d578587ca1");
    private static final UUID BOB_UUID =
            UUID.fromString("e4d12a4e-03de-4fce-a661-cac755e84117");
    private static final UUID SEASON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final java.time.Instant BASE_TIME =
            java.time.Instant.parse("2026-07-14T10:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesLiveSchemaWithVerifiedBackupAndPreservesData() throws Exception {
        Path database = temporaryDirectory.resolve("database.db");
        createLiveDatabase(database);

        Optional<Path> backup;
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.initialize();
            backup = repository.migrationBackup();
            DatabaseDoctorReport doctor = repository.inspectDatabase().get(5, TimeUnit.SECONDS);

            ScoreSnapshot snapshot = repository.snapshot();
            assertAll(
                    () -> assertEquals(2, snapshot.totalEntries()),
                    () -> assertEquals(149, snapshot.top().get(0).score()),
                    () -> assertEquals(62L, snapshot.top().get(0).id()),
                    () -> assertEquals(ALICE_UUID, snapshot.top().get(0).uuid().orElseThrow()),
                    () -> assertTrue(snapshot.top().get(0).updatedAt().isEmpty()),
                    () -> assertTrue(doctor.quickCheckPassed()),
                    () -> assertTrue(doctor.databaseBytes() > 0L),
                    () -> assertTrue(doctor.walBytes() >= 0L),
                    () -> assertEquals(1, doctor.migrationBackupCount()),
                    () -> assertTrue(doctor.latencyMillis() >= 0L));
        }

        assertTrue(backup.isPresent());
        Path backupPath = backup.orElseThrow();
        assertTrue(Files.isRegularFile(backupPath));
        assertSQLiteQuickCheck(backupPath);

        try (Connection connection = connect(database)) {
            assertAll(
                    () -> assertEquals(4, pragmaInt(connection, "user_version")),
                    () -> assertTrue(columnIsNullable(connection, "scoreboard", "updated_at")),
                    () -> assertTrue(tableExists(connection, "seasons")),
                    () -> assertTrue(tableExists(connection, "season_scores")),
                    () -> assertTrue(tableExists(connection, "run_history")),
                    () -> assertTrue(tableExists(connection, "reward_plans")),
                    () -> assertTrue(tableExists(connection, "reward_steps")),
                    () -> assertTrue(tableExists(connection, "reward_tombstones")),
                    () -> assertTrue(tableExists(connection, "player_preferences")),
                    () -> assertTrue(tableExists(connection, "category_scores")),
                    () -> assertTrue(tableExists(connection, "run_category_scores")),
                    () -> assertEquals(
                            Set.of("idx_scoreboard_ranking", "uq_scoreboard_uuid"),
                            repositoryIndexes(connection)),
                    () -> assertEquals(62L, queryLong(connection,
                            "SELECT id FROM scoreboard WHERE uuid = '"
                                    + ALICE_UUID + "'")),
                    () -> assertEquals(149, queryInt(connection,
                            "SELECT score FROM scoreboard WHERE uuid = '"
                                    + ALICE_UUID + "'")));
        }

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            assertTrue(repository.migrationBackup().isEmpty());
            assertEquals(2, repository.snapshot().totalEntries());
        }
    }

    @Test
    void migratesPopulatedSchemaThreeUsernameConstraintForFloodgateMetadata()
            throws Exception {
        Path database = temporaryDirectory.resolve("schema-three-floodgate.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.createSeason(SEASON_ID, "Migration Season", BASE_TIME)
                    .get(5, TimeUnit.SECONDS);
            repository.activateSeason(SEASON_ID, BASE_TIME.plusSeconds(1))
                    .get(5, TimeUnit.SECONDS);
            repository.startRun(new RunStart(
                            RUN_ID,
                            ALICE_UUID,
                            "JavaAlice",
                            "main",
                            BASE_TIME.plusSeconds(2),
                            "2.8.1-026"))
                    .get(5, TimeUnit.SECONDS);
            repository.completeRun(new RunCompletion(
                            RUN_ID,
                            BASE_TIME.plusSeconds(10),
                            42,
                            "FALL",
                            List.of(
                                    new RunCategoryScore(ScoreCategory.COMBO, 8),
                                    new RunCategoryScore(ScoreCategory.FLAWLESS, 42))))
                    .get(5, TimeUnit.SECONDS);
        }

        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX uq_scoreboard_uuid");
            statement.executeUpdate("DROP INDEX idx_scoreboard_ranking");
            statement.executeUpdate(
                    "ALTER TABLE scoreboard RENAME TO scoreboard_schema_four");
            statement.executeUpdate("""
                    CREATE TABLE scoreboard (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        score INTEGER NOT NULL DEFAULT 0
                            CHECK (typeof(score) = 'integer'
                                AND score >= 0 AND score <= 2147483647),
                        uuid VARCHAR(36) NULL,
                        username VARCHAR(128) NOT NULL
                            CHECK (typeof(username) = 'text'
                                AND length(username) BETWEEN 3 AND 16
                                AND username NOT GLOB '*[^A-Za-z0-9_]*'),
                        updated_at TIMESTAMP NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO scoreboard (id, score, uuid, username, updated_at)
                    SELECT id, score, uuid, username, updated_at
                    FROM scoreboard_schema_four
                    """);
            statement.executeUpdate("DROP TABLE scoreboard_schema_four");
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX uq_scoreboard_uuid
                    ON scoreboard(uuid COLLATE NOCASE)
                    WHERE uuid IS NOT NULL
                        AND trim(uuid, char(9) || char(10) || char(11)
                            || char(12) || char(13) || char(32)) <> ''
                    """);
            statement.executeUpdate(
                    "CREATE INDEX idx_scoreboard_ranking "
                            + "ON scoreboard(score DESC, id ASC)");
            statement.executeUpdate(
                    "DELETE FROM schema_migrations WHERE version = 4");
            statement.execute("PRAGMA user_version = 3");
        }

        UUID floodgateUuid =
                UUID.fromString("00000000-0000-0000-0009-01f7f208c5b7");
        Optional<Path> backup;
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            backup = repository.migrationBackup();
            repository.recordScore(floodgateUuid, ".Fedecito2579", 9)
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals(
                            42,
                            repository.stats(ALICE_UUID).orElseThrow().bestScore()),
                    () -> assertEquals(
                            "JavaAlice",
                            repository.stats(ALICE_UUID).orElseThrow().username()),
                    () -> assertEquals(
                            42,
                            repository.activeSeasonScores()
                                    .orElseThrow()
                                    .stats(ALICE_UUID)
                                    .orElseThrow()
                                    .bestScore()),
                    () -> assertEquals(
                            8,
                            repository.categoryStats(
                                            ScoreCategory.COMBO, ALICE_UUID)
                                    .orElseThrow()
                                    .bestScore()),
                    () -> assertEquals(
                            42,
                            repository.categoryStats(
                                            ScoreCategory.FLAWLESS, ALICE_UUID)
                                    .orElseThrow()
                                    .bestScore()),
                    () -> assertEquals(
                            ".Fedecito2579",
                            repository.stats(floodgateUuid).orElseThrow().username()));
        }

        assertTrue(backup.isPresent());
        assertSQLiteQuickCheck(backup.orElseThrow());
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            assertEquals(4, pragmaInt(connection, "user_version"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO scoreboard (score, username) "
                            + "VALUES (1, '.Bad-Name')"));
        }
    }

    @Test
    void rejectsSameNameIndexWithWrongDefinition() throws Exception {
        Path database = temporaryDirectory.resolve("drifted-index.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
        }
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX uq_seasons_active");
            statement.executeUpdate(
                    "CREATE INDEX uq_seasons_active ON seasons(status)");
        }

        try (JdbcScoreRepository repository = repository(database)) {
            ScoreRepositoryException failure = assertThrows(
                    ScoreRepositoryException.class, repository::initialize);
            assertTrue(failure.getMessage().contains("uq_seasons_active"));
        }
    }

    @Test
    void rejectsDurabilityTableMissingRequiredForeignKey() throws Exception {
        Path database = temporaryDirectory.resolve("missing-foreign-key.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
        }
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TABLE reward_steps");
            statement.executeUpdate("""
                    CREATE TABLE reward_steps (
                        plan_id VARCHAR(36) NOT NULL,
                        step_index INTEGER NOT NULL CHECK (step_index >= 0),
                        command_root VARCHAR(128) NOT NULL,
                        command_hash VARCHAR(64) NOT NULL,
                        status VARCHAR(16) NOT NULL CHECK (status IN (
                            'PENDING', 'DISPATCHING', 'SUCCEEDED', 'FAILED',
                            'UNKNOWN', 'SKIPPED')),
                        attempted_at INTEGER NULL,
                        completed_at INTEGER NULL,
                        PRIMARY KEY (plan_id, step_index)
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX idx_reward_steps_status ON reward_steps(status, plan_id)");
        }

        try (JdbcScoreRepository repository = repository(database)) {
            ScoreRepositoryException failure = assertThrows(
                    ScoreRepositoryException.class, repository::initialize);
            assertTrue(failure.getMessage().contains("foreign-key"));
        }
    }

    @Test
    void unresolvedLegacyRowsRemainVisibleAndAreNeverClaimedByName() throws Exception {
        Path database = temporaryDirectory.resolve("missing-uuid.db");
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE scoreboard (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        score INTEGER NOT NULL DEFAULT 0,
                        username VARCHAR(128) NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "INSERT INTO scoreboard (id, score, username) VALUES (91, 77, 'LegacyAlice')");
        }

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            ScoreEntry unresolved = repository.top().get(0);
            assertAll(
                    () -> assertEquals(91L, unresolved.id()),
                    () -> assertEquals(77, unresolved.score()),
                    () -> assertTrue(unresolved.uuid().isEmpty()),
                    () -> assertTrue(repository.stats(ALICE_UUID).isEmpty()));

            repository.touchIdentity(ALICE_UUID, "LegacyAlice").get(5, TimeUnit.SECONDS);
            assertEquals(1, repository.snapshot().totalEntries());
            assertTrue(repository.stats(ALICE_UUID).isEmpty());

            repository.recordScore(ALICE_UUID, "LegacyAlice", 12).get(5, TimeUnit.SECONDS);
            assertEquals(2, repository.snapshot().totalEntries());
            assertEquals(12, repository.stats(ALICE_UUID).orElseThrow().bestScore());
        }
    }

    @Test
    void rejectsNegativeLegacyScoreBeforeMigrationOrBackup() throws Exception {
        Path database = temporaryDirectory.resolve("negative-legacy-score.db");
        createLegacyScoreboard(database, -1L, "LegacyAlice");

        try (JdbcScoreRepository repository = repository(database)) {
            ScoreRepositoryException failure = assertThrows(
                    ScoreRepositoryException.class, repository::initialize);
            assertAll(
                    () -> assertTrue(failure.getMessage().contains("negative score")),
                    () -> assertTrue(repository.migrationBackup().isEmpty()));
        }

        assertAll(
                () -> assertEquals("-1", queryString(
                        database, "SELECT score FROM scoreboard")),
                () -> assertFalse(columnExists(database, "scoreboard", "uuid")),
                () -> assertFalse(columnExists(database, "scoreboard", "updated_at")));
    }

    @Test
    void rejectsUnsafeLegacyUsernamesBeforeMigration() throws Exception {
        List<String> invalidUsernames = List.of(
                "   ",
                "x".repeat(129),
                "Control\nName",
                "AB",
                "&cAdmin",
                "\u00a7cAdmin",
                "UnicodeName\u00e9",
                "=Formula");

        for (int index = 0; index < invalidUsernames.size(); index++) {
            Path database = temporaryDirectory.resolve("unsafe-username-" + index + ".db");
            createLegacyScoreboard(database, 12L, invalidUsernames.get(index));

            try (JdbcScoreRepository repository = repository(database)) {
                ScoreRepositoryException failure = assertThrows(
                        ScoreRepositoryException.class, repository::initialize);
                assertAll(
                        () -> assertTrue(failure.getMessage().contains("invalid username")),
                        () -> assertTrue(repository.migrationBackup().isEmpty()));
            }

            assertAll(
                    () -> assertFalse(columnExists(database, "scoreboard", "uuid")),
                    () -> assertFalse(columnExists(database, "scoreboard", "updated_at")));
        }
    }

    @Test
    void freshSchemaConstrainsScoreRangeAndStillSupportsZero() throws Exception {
        Path database = temporaryDirectory.resolve("fresh-score-constraint.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
        }

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO scoreboard (score, username) VALUES (-1, 'Negative')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO scoreboard (score, username) VALUES (2147483648, 'Overflow')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO scoreboard (score, username) VALUES (1, '&cAdmin')"));
            statement.executeUpdate(
                    "INSERT INTO scoreboard (score, username) VALUES (0, 'ZeroScore')");
        }

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            assertAll(
                    () -> assertEquals(1, repository.snapshot().totalEntries()),
                    () -> assertEquals(0, repository.top().get(0).score()));
        }
    }

    @Test
    void rejectsNegativeScoreInjectedIntoExistingSchemaBeforeSnapshot() throws Exception {
        Path database = temporaryDirectory.resolve("negative-existing-score.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.recordScore(ALICE_UUID, "Alice", 42).get(5, TimeUnit.SECONDS);
        }

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.executeUpdate("UPDATE scoreboard SET score = -42");
        }

        try (JdbcScoreRepository repository = repository(database)) {
            ScoreRepositoryException failure = assertThrows(
                    ScoreRepositoryException.class, repository::initialize);
            assertAll(
                    () -> assertTrue(failure.getMessage().contains("negative score")),
                    () -> assertEquals(0, repository.snapshot().totalEntries()));
        }
    }

    @Test
    void runtimeIdentityMetadataAcceptsNarrowFloodgateNamesAndRejectsUnsafeNames()
            throws Exception {
        Path database = temporaryDirectory.resolve("runtime-name-validation.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();

            UUID floodgateUuid =
                    UUID.fromString("00000000-0000-0000-0009-01f7f208c5b7");
            repository.touchIdentity(floodgateUuid, ".Fedecito2579")
                    .get(5, TimeUnit.SECONDS);
            ScoreUpdateResult floodgate = repository.recordScore(
                            floodgateUuid, ".Fedecito2579", 10)
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals(floodgateUuid, floodgate.uuid()),
                    () -> assertEquals(".Fedecito2579", floodgate.username()),
                    () -> assertEquals(
                            ".Fedecito2579",
                            repository.stats(floodgateUuid).orElseThrow().username()));

            for (String username : List.of(
                    "&cAdmin",
                    "\u00a7cAdmin",
                    "=Formula",
                    "AB",
                    ".",
                    "..Alice",
                    "Alice.",
                    ".Bad-Name",
                    ".Bad Name",
                    "." + "A".repeat(17))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> repository.touchIdentity(ALICE_UUID, username));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> repository.recordScore(ALICE_UUID, username, 10));
            }
            assertThrows(IllegalArgumentException.class, () -> new RunStart(
                    RUN_ID,
                    ALICE_UUID,
                    "&cAdmin",
                    "main",
                    BASE_TIME,
                    "2.1.0-003"));
            assertThrows(IllegalArgumentException.class, () -> new CompletedRun(
                    RUN_ID,
                    ALICE_UUID,
                    "\u00a7cAdmin",
                    "main",
                    BASE_TIME,
                    BASE_TIME.plusSeconds(5),
                    10,
                    "FELL",
                    "2.1.0-003",
                    Optional.empty()));

            repository.recordScore(ALICE_UUID, "Player_123", 0)
                    .get(5, TimeUnit.SECONDS);
            repository.recordScore(BOB_UUID, ".A", 0)
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals(3, repository.snapshot().totalEntries()),
                    () -> assertEquals(".Fedecito2579", repository.top().get(0).username()));
        }
    }

    @Test
    void rejectsUnsafeExistingSeasonScoreNameBeforeSnapshot() throws Exception {
        Path database = temporaryDirectory.resolve("unsafe-season-score-name.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.createSeason(SEASON_ID, "Summer 2026", BASE_TIME)
                    .get(5, TimeUnit.SECONDS);
            repository.activateSeason(SEASON_ID, BASE_TIME.plusSeconds(1))
                    .get(5, TimeUnit.SECONDS);
            repository.recordCompletedRun(completedRun(RUN_ID, ALICE_UUID, 12))
                    .get(5, TimeUnit.SECONDS);
        }

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.executeUpdate("UPDATE season_scores SET username = '&cAdmin'");
        }

        try (JdbcScoreRepository repository = repository(database)) {
            ScoreRepositoryException failure = assertThrows(
                    ScoreRepositoryException.class, repository::initialize);
            assertAll(
                    () -> assertTrue(failure.getMessage().contains("season_scores")),
                    () -> assertTrue(failure.getMessage().contains("invalid username")),
                    () -> assertEquals(0, repository.snapshot().totalEntries()));
        }
    }

    @Test
    void rejectsMalformedAndDuplicateNonblankUuidsWithoutRewritingThem() throws Exception {
        Path malformedDatabase = temporaryDirectory.resolve("malformed.db");
        createIdentityDatabase(malformedDatabase, List.of(
                new LegacyIdentity(3L, 20, "not-a-uuid", "Malformed")));

        try (JdbcScoreRepository repository = repository(malformedDatabase)) {
            ScoreRepositoryException exception = assertThrows(
                    ScoreRepositoryException.class, repository::initialize);
            assertTrue(exception.getMessage().contains("Invalid nonblank UUID"));
        }
        assertEquals("not-a-uuid", queryString(
                malformedDatabase, "SELECT uuid FROM scoreboard WHERE id = 3"));
        assertFalse(columnExists(malformedDatabase, "scoreboard", "updated_at"));

        Path duplicateDatabase = temporaryDirectory.resolve("duplicate.db");
        createIdentityDatabase(duplicateDatabase, List.of(
                new LegacyIdentity(7L, 40, ALICE_UUID.toString(), "Alice"),
                new LegacyIdentity(8L, 30, ALICE_UUID.toString().toUpperCase(), "AliceOld")));

        try (JdbcScoreRepository repository = repository(duplicateDatabase)) {
            ScoreRepositoryException exception = assertThrows(
                    ScoreRepositoryException.class, repository::initialize);
            assertTrue(exception.getMessage().contains("Duplicate nonblank UUID"));
        }
        assertEquals(ALICE_UUID.toString().toUpperCase(), queryString(
                duplicateDatabase, "SELECT uuid FROM scoreboard WHERE id = 8"));
        assertFalse(columnExists(duplicateDatabase, "scoreboard", "updated_at"));
    }

    @Test
    void upsertsPersonalBestAndRefreshesNameWithoutCreatingJoinRows() throws Exception {
        Path database = temporaryDirectory.resolve("upsert.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();

            repository.touchIdentity(ALICE_UUID, "Alice").get(5, TimeUnit.SECONDS);
            assertEquals(0, repository.snapshot().totalEntries());

            ScoreUpdateResult first = repository.recordScore(ALICE_UUID, "Alice", 42)
                    .get(5, TimeUnit.SECONDS);
            ScoreUpdateResult lower = repository.recordScore(ALICE_UUID, "AliceRenamed", 12)
                    .get(5, TimeUnit.SECONDS);
            repository.touchIdentity(ALICE_UUID, "AliceFinal").get(5, TimeUnit.SECONDS);

            PlayerStats stats = repository.stats(ALICE_UUID).orElseThrow();
            assertAll(
                    () -> assertTrue(first.created()),
                    () -> assertTrue(first.newBest()),
                    () -> assertEquals(42, first.bestScore()),
                    () -> assertFalse(lower.created()),
                    () -> assertFalse(lower.newBest()),
                    () -> assertEquals(42, lower.previousBestScore()),
                    () -> assertEquals(42, lower.bestScore()),
                    () -> assertEquals("AliceFinal", stats.username()),
                    () -> assertEquals(42, stats.bestScore()),
                    () -> assertEquals(1, repository.snapshot().totalEntries()),
                    () -> assertTrue(repository.top().get(0).updatedAt().isPresent()));
        }

        try (Connection connection = connect(database)) {
            assertAll(
                    () -> assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM scoreboard")),
                    () -> assertEquals(42, queryInt(connection, "SELECT score FROM scoreboard")),
                    () -> assertEquals("AliceFinal", queryString(
                            database, "SELECT username FROM scoreboard")));
        }
    }

    @Test
    void snapshotUsesCompetitionRanksStableIdOrderingAndTenRowLimit() throws Exception {
        Path database = temporaryDirectory.resolve("ranking.db");
        int[] submittedScores = {100, 90, 90, 80, 70, 60, 50, 40, 30, 20, 10, 5};

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            for (int index = 0; index < submittedScores.length; index++) {
                UUID uuid = new UUID(0L, index + 1L);
                repository.recordScore(uuid, "Player" + index, submittedScores[index])
                        .get(5, TimeUnit.SECONDS);
            }

            ScoreSnapshot snapshot = repository.snapshot();
            assertAll(
                    () -> assertEquals(12, snapshot.scores().size()),
                    () -> assertEquals(10, snapshot.top().size()),
                    () -> assertEquals(List.of(1, 2, 2, 4), snapshot.scores().stream()
                            .limit(4)
                            .map(ScoreEntry::rank)
                            .toList()),
                    () -> assertTrue(snapshot.scores().get(1).id() < snapshot.scores().get(2).id()),
                    () -> assertEquals(2, snapshot.stats(new UUID(0L, 2L)).orElseThrow().rank()),
                    () -> assertThrows(
                            UnsupportedOperationException.class,
                            () -> snapshot.top().clear()));
        }
    }

    @Test
    void seasonsCaptureAtStartAndMaintainSeparateCurrentAndAllTimeViews() throws Exception {
        Path database = temporaryDirectory.resolve("seasons.db");
        UUID secondSeasonId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            Season planned = repository.createSeason(SEASON_ID, "Summer 2026", BASE_TIME)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(SeasonStatus.PLANNED, planned.status());
            Season active = repository.activateSeason(
                            SEASON_ID, BASE_TIME.plusSeconds(10))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(SeasonStatus.ACTIVE, active.status());

            RunRecord started = repository.startRun(new RunStart(
                            RUN_ID,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME.plusSeconds(20),
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of(SEASON_ID), started.seasonId());
            assertEquals(RunStatus.STARTED, started.status());

            repository.closeSeason(SEASON_ID, BASE_TIME.plusSeconds(30))
                    .get(5, TimeUnit.SECONDS);
            CompletedRunResult completed = repository.completeRun(new RunCompletion(
                            RUN_ID,
                            BASE_TIME.plusSeconds(40),
                            87,
                            "FELL"))
                    .get(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(completed.created()),
                    () -> assertEquals(RunStatus.COMPLETED, completed.run().status()),
                    () -> assertEquals(87, completed.allTimeScore().orElseThrow().bestScore()),
                    () -> assertEquals(87, completed.seasonScore().orElseThrow().bestScore()),
                    () -> assertEquals(87, repository.stats(ALICE_UUID).orElseThrow().bestScore()),
                    () -> assertTrue(repository.activeSeason().isEmpty()),
                    () -> assertTrue(repository.activeSeasonScores().isEmpty()));

            ScoreSnapshot historical = repository.seasonScores(SEASON_ID)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(87, historical.stats(ALICE_UUID).orElseThrow().bestScore());

            Season reopened = repository.reopenSeason(
                            SEASON_ID, BASE_TIME.plusSeconds(50))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(SeasonStatus.PLANNED, reopened.status());
            repository.activateSeason(SEASON_ID, BASE_TIME.plusSeconds(60))
                    .get(5, TimeUnit.SECONDS);
            repository.createSeason(secondSeasonId, "Autumn 2026", BASE_TIME.plusSeconds(61))
                    .get(5, TimeUnit.SECONDS);
            ExecutionException conflict = assertThrows(
                    ExecutionException.class,
                    () -> repository.activateSeason(secondSeasonId, BASE_TIME.plusSeconds(62))
                            .get(5, TimeUnit.SECONDS));
            assertTrue(conflict.getCause() instanceof ScoreRepositoryException);
            repository.closeSeason(SEASON_ID, BASE_TIME.plusSeconds(70))
                    .get(5, TimeUnit.SECONDS);
            Season archived = repository.archiveSeason(
                            SEASON_ID, BASE_TIME.plusSeconds(80))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(SeasonStatus.ARCHIVED, archived.status());
        }
    }

    @Test
    void seasonTransitionsRejectTimestampsBeforeCurrentTransition() throws Exception {
        Path database = temporaryDirectory.resolve("season-transition-order.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.createSeason(SEASON_ID, "Summer 2026", BASE_TIME)
                    .get(5, TimeUnit.SECONDS);
            repository.activateSeason(SEASON_ID, BASE_TIME.plusSeconds(10))
                    .get(5, TimeUnit.SECONDS);
            assertSeasonTransitionRejected(
                    repository.closeSeason(SEASON_ID, BASE_TIME.plusSeconds(9)));

            repository.closeSeason(SEASON_ID, BASE_TIME.plusSeconds(20))
                    .get(5, TimeUnit.SECONDS);
            assertSeasonTransitionRejected(
                    repository.reopenSeason(SEASON_ID, BASE_TIME.plusSeconds(19)));

            repository.reopenSeason(SEASON_ID, BASE_TIME.plusSeconds(30))
                    .get(5, TimeUnit.SECONDS);
            assertSeasonTransitionRejected(
                    repository.activateSeason(SEASON_ID, BASE_TIME.plusSeconds(29)));

            repository.activateSeason(SEASON_ID, BASE_TIME.plusSeconds(40))
                    .get(5, TimeUnit.SECONDS);
            repository.closeSeason(SEASON_ID, BASE_TIME.plusSeconds(50))
                    .get(5, TimeUnit.SECONDS);
            assertSeasonTransitionRejected(
                    repository.archiveSeason(SEASON_ID, BASE_TIME.plusSeconds(49)));
        }
    }

    @Test
    void zeroScoreRunsRemainInHistoryWithoutCreatingLeaderboardRows() throws Exception {
        Path database = temporaryDirectory.resolve("zero-score-run.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.createSeason(SEASON_ID, "Summer 2026", BASE_TIME)
                    .get(5, TimeUnit.SECONDS);
            repository.activateSeason(SEASON_ID, BASE_TIME.plusSeconds(1))
                    .get(5, TimeUnit.SECONDS);

            CompletedRunResult completed = repository.recordCompletedRun(new CompletedRun(
                            RUN_ID,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME.plusSeconds(2),
                            BASE_TIME.plusSeconds(3),
                            0,
                            "FELL",
                            "2.1.0-003",
                            Optional.empty()))
                    .get(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals(RunStatus.COMPLETED, completed.run().status()),
                    () -> assertEquals(Optional.of(0), completed.run().score()),
                    () -> assertTrue(completed.allTimeScore().isEmpty()),
                    () -> assertTrue(completed.seasonScore().isEmpty()),
                    () -> assertTrue(repository.stats(ALICE_UUID).isEmpty()),
                    () -> assertTrue(repository.activeSeasonScores()
                            .orElseThrow()
                            .stats(ALICE_UUID)
                            .isEmpty()),
                    () -> assertEquals(RUN_ID, repository.recentRuns(1).getFirst().id()));

            CompletedRunResult duplicate = repository.completeRun(new RunCompletion(
                            RUN_ID,
                            BASE_TIME.plusSeconds(3),
                            0,
                            "FELL"))
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertFalse(duplicate.created()),
                    () -> assertTrue(duplicate.allTimeScore().isEmpty()),
                    () -> assertTrue(duplicate.seasonScore().isEmpty()));
        }
    }

    @Test
    void atomicallyProjectsComboAndFlawlessWithoutChangingClassicSemantics()
            throws Exception {
        Path database = temporaryDirectory.resolve("category-scores.db");
        RunCompletion completion = new RunCompletion(
                RUN_ID,
                BASE_TIME.plusSeconds(20),
                12,
                "FALL",
                List.of(
                        new RunCategoryScore(ScoreCategory.COMBO, 8),
                        new RunCategoryScore(ScoreCategory.FLAWLESS, 12)));
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.startRun(new RunStart(
                            RUN_ID,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME,
                            "2.4.0-008"))
                    .get(5, TimeUnit.SECONDS);

            CompletedRunResult first = repository.completeRun(completion)
                    .get(5, TimeUnit.SECONDS);
            CompletedRunResult retry = repository.completeRun(completion)
                    .get(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(first.created()),
                    () -> assertFalse(retry.created()),
                    () -> assertEquals(12, repository.stats(ALICE_UUID)
                            .orElseThrow()
                            .bestScore()),
                    () -> assertEquals(8, repository.categoryStats(
                                    ScoreCategory.COMBO, ALICE_UUID)
                            .orElseThrow()
                            .bestScore()),
                    () -> assertEquals(12, repository.categoryStats(
                                    ScoreCategory.FLAWLESS, ALICE_UUID)
                            .orElseThrow()
                            .bestScore()),
                    () -> assertEquals(
                            Set.of(ScoreCategory.COMBO, ScoreCategory.FLAWLESS),
                            first.categoryScores().keySet()),
                    () -> assertEquals(
                            first.categoryScores().keySet(),
                            retry.categoryScores().keySet()),
                    () -> assertEquals(
                            8,
                            retry.categoryScores()
                                    .get(ScoreCategory.COMBO)
                                    .bestScore()),
                    () -> assertFalse(
                            retry.categoryScores()
                                    .get(ScoreCategory.COMBO)
                                    .newBest()));

            RunCompletion conflictingRetry = new RunCompletion(
                    RUN_ID,
                    completion.endedAt(),
                    12,
                    "FALL",
                    List.of(new RunCategoryScore(ScoreCategory.COMBO, 7)));
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> repository.completeRun(conflictingRetry)
                            .get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ScoreRepositoryException);
            assertEquals(8, repository.categoryStats(
                            ScoreCategory.COMBO, ALICE_UUID)
                    .orElseThrow()
                    .bestScore());
        }
    }

    @Test
    void persistsUuidOwnedAccessibilityPreferencesAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("preferences.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            assertEquals(
                    PlayerPreferences.defaults(ALICE_UUID),
                    repository.preferences(ALICE_UUID));
            PlayerPreferences saved = repository.updatePreferences(
                            new PlayerPreferences(
                                    ALICE_UUID,
                                    ParticlePreference.REDUCED,
                                    false,
                                    false,
                                    BASE_TIME))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(saved, repository.preferences(ALICE_UUID));
            assertTrue(repository.stats(ALICE_UUID).isEmpty());
        }
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            PlayerPreferences loaded = repository.preferences(ALICE_UUID);
            assertAll(
                    () -> assertEquals(ParticlePreference.REDUCED, loaded.particles()),
                    () -> assertFalse(loaded.soundsEnabled()),
                    () -> assertFalse(loaded.titlesEnabled()),
                    () -> assertEquals(BASE_TIME, loaded.updatedAt()),
                    () -> assertEquals(0, repository.snapshot().totalEntries()));
        }
    }

    @Test
    void simultaneousPreferenceFieldUpdatesPreserveEverySetting()
            throws Exception {
        Path database = temporaryDirectory.resolve("preferences.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            var particles = repository.updateParticlePreference(
                    ALICE_UUID, ParticlePreference.REDUCED, BASE_TIME);
            var sounds = repository.updateSoundPreference(
                    ALICE_UUID, false, BASE_TIME.plusMillis(1));
            var titles = repository.updateTitlePreference(
                    ALICE_UUID, false, BASE_TIME.plusMillis(2));

            CompletableFuture.allOf(particles, sounds, titles)
                    .get(5, TimeUnit.SECONDS);

            PlayerPreferences saved = repository.preferences(ALICE_UUID);
            assertAll(
                    () -> assertEquals(ParticlePreference.REDUCED, saved.particles()),
                    () -> assertFalse(saved.soundsEnabled()),
                    () -> assertFalse(saved.titlesEnabled()),
                    () -> assertEquals(BASE_TIME.plusMillis(2), saved.updatedAt()),
                    () -> assertTrue(repository.stats(ALICE_UUID).isEmpty()));
        }
    }

    @Test
    void prunesOnlyVerifiedAutomaticBackupsAndRetainsOperatorFiles()
            throws Exception {
        Path database = temporaryDirectory.resolve("database.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
        }
        for (int index = 1; index <= 5; index++) {
            Files.copy(
                    database,
                    temporaryDirectory.resolve(
                            "database.db.pre-migration-v3-" + (1_000 + index) + ".sqlite"));
        }
        Path operatorBackup =
                temporaryDirectory.resolve("database.db.operator-summer-event.sqlite");
        Files.copy(database, operatorBackup);

        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database, Duration.ofSeconds(5), 2))) {
            repository.initialize();
            DatabaseDoctorReport doctor =
                    repository.inspectDatabase().get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals(2, doctor.migrationBackupCount()),
                    () -> assertEquals(2, doctor.migrationBackupRetention()),
                    () -> assertEquals(3, doctor.migrationBackupsPrunedAtStartup()),
                    () -> assertTrue(Files.isRegularFile(operatorBackup)));
        }
        try (var paths = Files.list(temporaryDirectory)) {
            assertEquals(2L, paths
                    .filter(path -> path.getFileName().toString()
                            .matches("database\\.db\\.pre-migration-v\\d+-\\d+(?:-\\d+)?\\.sqlite"))
                    .count());
        }
    }

    @Test
    void ordersMigrationBackupsByNumericTimestampAcrossSchemaVersions()
            throws Exception {
        Path database = temporaryDirectory.resolve("database.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
        }
        Path newest = temporaryDirectory.resolve(
                "database.db.pre-migration-v3-3000.sqlite");
        Path secondNewest = temporaryDirectory.resolve(
                "database.db.pre-migration-v9-2000.sqlite");
        Path oldest = temporaryDirectory.resolve(
                "database.db.pre-migration-v10-1000.sqlite");
        Files.copy(database, newest);
        Files.copy(database, secondNewest);
        Files.copy(database, oldest);

        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database, Duration.ofSeconds(5), 2))) {
            repository.initialize();
        }

        assertAll(
                () -> assertTrue(Files.isRegularFile(newest)),
                () -> assertTrue(Files.isRegularFile(secondNewest)),
                () -> assertFalse(Files.exists(oldest)));
    }

    @Test
    void interruptedStartedRunsBecomeUnknownAndRemainStaffResolvable() throws Exception {
        Path database = temporaryDirectory.resolve("interrupted-run.db");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.startRun(new RunStart(
                            RUN_ID,
                            BOB_UUID,
                            "Bob",
                            "main",
                            BASE_TIME,
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);
        }

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            RunRecord unknown = repository.recentRuns(1).get(0);
            assertAll(
                    () -> assertEquals(RunStatus.UNKNOWN, unknown.status()),
                    () -> assertEquals(Optional.of("PROCESS_INTERRUPTED"), unknown.endReason()),
                    () -> assertTrue(unknown.score().isEmpty()));

            RunRecord resolved = repository.markRunInterrupted(
                            RUN_ID,
                            RunStatus.ABORTED,
                            unknown.endedAt().orElseThrow().plusSeconds(1),
                            "STAFF_CONFIRMED_ABORT")
                    .get(5, TimeUnit.SECONDS);
            assertEquals(RunStatus.ABORTED, resolved.status());
        }
    }

    @Test
    void rewardLedgerSuppressesDuplicatesAndRecordsPartialPlansWithoutCommands() throws Exception {
        Path database = temporaryDirectory.resolve("reward-ledger.db");
        RewardPlanRequest request = rewardRequest(PLAN_ID, RUN_ID, "run:one");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.recordCompletedRun(completedRun(RUN_ID, ALICE_UUID, 55))
                    .get(5, TimeUnit.SECONDS);

            RewardPlanBeginResult begun = repository.prepareRewardPlan(request)
                    .get(5, TimeUnit.SECONDS);
            RewardPlanBeginResult duplicate = repository.prepareRewardPlan(request)
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertTrue(begun.created()),
                    () -> assertTrue(begun.shouldDispatch()),
                    () -> assertFalse(duplicate.created()),
                    () -> assertTrue(duplicate.shouldDispatch()));

            RewardStepDispatchResult firstClaim = repository.claimRewardStep(
                            PLAN_ID, 0, BASE_TIME.plusSeconds(20))
                    .get(5, TimeUnit.SECONDS);
            RewardStepDispatchResult duplicateClaim = repository.claimRewardStep(
                            PLAN_ID, 0, BASE_TIME.plusSeconds(20))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(firstClaim.shouldDispatch());
            assertFalse(duplicateClaim.shouldDispatch());
            repository.recordRewardStepOutcome(
                            PLAN_ID,
                            0,
                            RewardStepStatus.SUCCEEDED,
                            BASE_TIME.plusSeconds(21))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(repository.claimRewardStep(
                            PLAN_ID, 1, BASE_TIME.plusSeconds(22))
                    .get(5, TimeUnit.SECONDS).shouldDispatch());
            RewardPlanRecord partial = repository.recordRewardStepOutcome(
                            PLAN_ID,
                            1,
                            RewardStepStatus.FAILED,
                            BASE_TIME.plusSeconds(23))
                    .get(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals(RewardPlanStatus.PARTIAL, partial.status()),
                    () -> assertEquals(
                            RewardPlanStatus.PARTIAL,
                            repository.rewardPlan(PLAN_ID)
                                    .get(5, TimeUnit.SECONDS)
                                    .orElseThrow()
                                    .status()),
                    () -> assertEquals(
                            1L,
                            repository.durabilityMetrics()
                                    .rewardPlansByStatus()
                                    .get(RewardPlanStatus.PARTIAL)));
        }

        try (Connection connection = connect(database)) {
            assertFalse(columnExists(database, "reward_steps", "command"));
            assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM reward_steps WHERE command_root LIKE '%Alice%'"));
        }
    }

    @Test
    void atomicallyCompletesRunAndPersistsExactRewardIntent() throws Exception {
        Path database = temporaryDirectory.resolve("atomic-run-reward.db");
        RunCompletion completion = new RunCompletion(
                RUN_ID, BASE_TIME.plusSeconds(10), 55, "FELL");
        RewardPlanRequest request = new RewardPlanRequest(
                PLAN_ID,
                RUN_ID,
                "run:" + RUN_ID,
                completion.endedAt(),
                List.of(
                        new RewardStepSpec("give", "a".repeat(64)),
                        new RewardStepSpec("cmi", "b".repeat(64))));

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.startRun(new RunStart(
                            RUN_ID,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME,
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);

            CompletedRunWithRewardPlanResult first = repository.completeRunWithRewardPlan(
                            completion, Optional.of(request))
                    .get(5, TimeUnit.SECONDS);
            CompletedRunWithRewardPlanResult retry = repository.completeRunWithRewardPlan(
                            completion, Optional.of(request))
                    .get(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(first.completion().created()),
                    () -> assertTrue(first.rewardPlan().orElseThrow().created()),
                    () -> assertTrue(first.rewardPlan().orElseThrow().shouldDispatch()),
                    () -> assertEquals(
                            RewardPlanStatus.PENDING,
                            first.rewardPlan().orElseThrow().plan().status()),
                    () -> assertEquals(55, repository.stats(ALICE_UUID).orElseThrow().bestScore()),
                    () -> assertFalse(retry.completion().created()),
                    () -> assertFalse(retry.rewardPlan().orElseThrow().created()),
                    () -> assertFalse(retry.rewardPlan().orElseThrow().shouldDispatch()));
        }

        try (Connection connection = connect(database)) {
            assertAll(
                    () -> assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM run_history")),
                    () -> assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM reward_plans")),
                    () -> assertEquals(2, queryInt(connection, "SELECT COUNT(*) FROM reward_steps")));
        }
    }

    @Test
    void atomicRewardConflictRollsBackRunAndScoreProjection() throws Exception {
        Path database = temporaryDirectory.resolve("atomic-run-reward-rollback.db");
        UUID existingRunId = UUID.fromString("60000000-0000-0000-0000-000000000001");
        RunCompletion completion = new RunCompletion(
                RUN_ID, BASE_TIME.plusSeconds(10), 55, "FELL");
        RewardPlanRequest conflicting = new RewardPlanRequest(
                PLAN_ID,
                RUN_ID,
                "run:" + RUN_ID,
                completion.endedAt(),
                List.of(new RewardStepSpec("give", "d".repeat(64))));

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.recordCompletedRun(completedRun(existingRunId, BOB_UUID, 20))
                    .get(5, TimeUnit.SECONDS);
            repository.prepareRewardPlan(new RewardPlanRequest(
                            PLAN_ID,
                            existingRunId,
                            "existing-plan",
                            BASE_TIME.plusSeconds(30),
                            List.of(new RewardStepSpec("give", "e".repeat(64)))))
                    .get(5, TimeUnit.SECONDS);
            repository.startRun(new RunStart(
                            RUN_ID,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME,
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> repository.completeRunWithRewardPlan(
                                    completion, Optional.of(conflicting))
                            .get(5, TimeUnit.SECONDS));

            RunRecord retainedStart = repository.recentRuns(100).stream()
                    .filter(run -> run.id().equals(RUN_ID))
                    .findFirst()
                    .orElseThrow();
            assertAll(
                    () -> assertTrue(failure.getCause() instanceof ScoreRepositoryException),
                    () -> assertEquals(RunStatus.STARTED, retainedStart.status()),
                    () -> assertTrue(repository.stats(ALICE_UUID).isEmpty()),
                    () -> assertEquals(
                            existingRunId,
                            repository.rewardPlan(PLAN_ID)
                                    .get(5, TimeUnit.SECONDS)
                                    .orElseThrow()
                                    .runId()));
        }
    }

    @Test
    void atomicCompletionNeverRetroactivelyBackfillsRewardIntent() throws Exception {
        Path database = temporaryDirectory.resolve("atomic-no-backfill.db");
        RunCompletion completion = new RunCompletion(
                RUN_ID, BASE_TIME.plusSeconds(10), 25, "FELL");
        RewardPlanRequest request = new RewardPlanRequest(
                PLAN_ID,
                RUN_ID,
                "run:" + RUN_ID,
                completion.endedAt(),
                List.of(new RewardStepSpec("give", "f".repeat(64))));

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.startRun(new RunStart(
                            RUN_ID,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME,
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);
            repository.completeRun(completion).get(5, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> repository.completeRunWithRewardPlan(
                                    completion, Optional.of(request))
                            .get(5, TimeUnit.SECONDS));
            assertAll(
                    () -> assertTrue(failure.getCause() instanceof ScoreRepositoryException),
                    () -> assertTrue(repository.rewardPlan(PLAN_ID)
                            .get(5, TimeUnit.SECONDS)
                            .isEmpty()),
                    () -> assertEquals(25, repository.stats(ALICE_UUID)
                            .orElseThrow()
                            .bestScore()));
        }
    }

    @Test
    void startupFreezesClaimedRewardAsUnknownAndNeverReplaysIt() throws Exception {
        Path database = temporaryDirectory.resolve("reward-unknown.db");
        RewardPlanRequest request = new RewardPlanRequest(
                PLAN_ID,
                RUN_ID,
                "run:unknown",
                BASE_TIME.plusSeconds(10),
                List.of(new RewardStepSpec("give", "c".repeat(64))));
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.recordCompletedRun(completedRun(RUN_ID, BOB_UUID, 20))
                    .get(5, TimeUnit.SECONDS);
            repository.prepareRewardPlan(request).get(5, TimeUnit.SECONDS);
            assertTrue(repository.claimRewardStep(PLAN_ID, 0, BASE_TIME.plusSeconds(20))
                    .get(5, TimeUnit.SECONDS).shouldDispatch());
        }

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            RewardPlanRecord recovered = repository.rewardPlan(PLAN_ID)
                    .get(5, TimeUnit.SECONDS)
                    .orElseThrow();
            RewardPlanBeginResult duplicate = repository.prepareRewardPlan(request)
                    .get(5, TimeUnit.SECONDS);
            List<RewardPlanRecord> unknownPlans = repository.recentRewardPlans(
                            RewardPlanStatus.UNKNOWN, 10)
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals(RewardPlanStatus.UNKNOWN, recovered.status()),
                    () -> assertEquals(RewardStepStatus.UNKNOWN, recovered.steps().get(0).status()),
                    () -> assertFalse(duplicate.shouldDispatch()),
                    () -> assertEquals(List.of(PLAN_ID), unknownPlans.stream()
                            .map(RewardPlanRecord::planId)
                            .toList()),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> repository.recentRewardPlans(RewardPlanStatus.UNKNOWN, 0)));

            RewardPlanRecord resolved = repository.resolveUnknownRewardStep(
                            PLAN_ID,
                            0,
                            RewardStepStatus.SUCCEEDED,
                            BASE_TIME.plusSeconds(30))
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals(RewardPlanStatus.SUCCEEDED, resolved.status()),
                    () -> assertEquals(
                            RewardStepStatus.SUCCEEDED,
                            resolved.steps().get(0).status()),
                    () -> assertThrows(
                            ExecutionException.class,
                            () -> repository.resolveUnknownRewardStep(
                                            PLAN_ID,
                                            0,
                                            RewardStepStatus.FAILED,
                                            BASE_TIME.plusSeconds(31))
                                    .get(5, TimeUnit.SECONDS)),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> repository.resolveUnknownRewardStep(
                                    PLAN_ID,
                                    0,
                                    RewardStepStatus.UNKNOWN,
                                    BASE_TIME.plusSeconds(31))));
        }
    }

    @Test
    void exactPendingRewardPlanSurvivesRestartAndRemainsClaimable() throws Exception {
        Path database = temporaryDirectory.resolve("reward-pending-resume.db");
        RewardPlanRequest request = rewardRequest(PLAN_ID, RUN_ID, "pending-resume");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.recordCompletedRun(completedRun(RUN_ID, ALICE_UUID, 40))
                    .get(5, TimeUnit.SECONDS);
            repository.prepareRewardPlan(request).get(5, TimeUnit.SECONDS);
        }

        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            RewardPlanBeginResult resumed = repository.prepareRewardPlan(request)
                    .get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertFalse(resumed.created()),
                    () -> assertTrue(resumed.shouldDispatch()),
                    () -> assertEquals(RewardPlanStatus.PENDING, resumed.plan().status()),
                    () -> assertTrue(repository.claimRewardStep(
                                    PLAN_ID, 0, BASE_TIME.plusSeconds(70))
                            .get(5, TimeUnit.SECONDS)
                            .shouldDispatch()),
                    () -> assertFalse(repository.claimRewardStep(
                                    PLAN_ID, 0, BASE_TIME.plusSeconds(70))
                            .get(5, TimeUnit.SECONDS)
                            .shouldDispatch()));
        }
    }

    @Test
    void rewardPlansRequireCompletedRuns() throws Exception {
        Path database = temporaryDirectory.resolve("reward-completed-only.db");
        RewardPlanRequest request = rewardRequest(PLAN_ID, RUN_ID, "completed-only");
        try (JdbcScoreRepository repository = repository(database)) {
            repository.initialize();
            repository.startRun(new RunStart(
                            RUN_ID,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME,
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);

            ExecutionException startedFailure = assertThrows(
                    ExecutionException.class,
                    () -> repository.prepareRewardPlan(request).get(5, TimeUnit.SECONDS));
            assertTrue(startedFailure.getCause() instanceof ScoreRepositoryException);

            repository.markRunInterrupted(
                            RUN_ID,
                            RunStatus.ABORTED,
                            BASE_TIME.plusSeconds(5),
                            "STAFF_ABORT")
                    .get(5, TimeUnit.SECONDS);
            ExecutionException abortedFailure = assertThrows(
                    ExecutionException.class,
                    () -> repository.prepareRewardPlan(request).get(5, TimeUnit.SECONDS));
            assertTrue(abortedFailure.getCause() instanceof ScoreRepositoryException);
        }
    }

    @Test
    void completingAnOlderStartedRunCannotPruneItsOwnResult() throws Exception {
        Path database = temporaryDirectory.resolve("retention-self-prune.db");
        UUID olderRunId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID newerRunId = UUID.fromString("40000000-0000-0000-0000-000000000002");
        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database), Logger.getAnonymousLogger(), 1)) {
            repository.initialize();
            repository.startRun(new RunStart(
                            olderRunId,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME,
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);
            repository.recordCompletedRun(completedRun(newerRunId, BOB_UUID, 20))
                    .get(5, TimeUnit.SECONDS);

            CompletedRunResult completed = repository.completeRun(new RunCompletion(
                            olderRunId,
                            BASE_TIME.plusSeconds(100),
                            25,
                            "FELL"))
                    .get(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals(RunStatus.COMPLETED, completed.run().status()),
                    () -> assertEquals(olderRunId, repository.recentRuns(1).get(0).id()),
                    () -> assertEquals(1L, repository.durabilityMetrics().retainedRuns()));
        }
    }

    @Test
    void retentionPreservesUnsettledRewardLedgers() throws Exception {
        Path database = temporaryDirectory.resolve("retention-unsettled.db");
        UUID protectedRunId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        RewardPlanRequest request = new RewardPlanRequest(
                PLAN_ID,
                protectedRunId,
                "protected-ledger",
                BASE_TIME.plusSeconds(50),
                List.of(new RewardStepSpec("give", "d".repeat(64))));
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);

        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database), logger, 1)) {
            repository.initialize();
            repository.recordCompletedRun(completedRun(protectedRunId, ALICE_UUID, 30))
                    .get(5, TimeUnit.SECONDS);
            repository.prepareRewardPlan(request).get(5, TimeUnit.SECONDS);
            repository.recordCompletedRun(completedRun(
                            UUID.fromString("50000000-0000-0000-0000-000000000002"),
                            BOB_UUID,
                            31))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(
                    RewardPlanStatus.PENDING,
                    repository.rewardPlan(PLAN_ID).get(5, TimeUnit.SECONDS).orElseThrow().status());

            assertTrue(repository.claimRewardStep(PLAN_ID, 0, BASE_TIME.plusSeconds(60))
                    .get(5, TimeUnit.SECONDS).shouldDispatch());
            repository.recordCompletedRun(completedRun(
                            UUID.fromString("50000000-0000-0000-0000-000000000003"),
                            BOB_UUID,
                            32))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(
                    RewardPlanStatus.IN_PROGRESS,
                    repository.rewardPlan(PLAN_ID).get(5, TimeUnit.SECONDS).orElseThrow().status());
        }

        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database), logger, 1)) {
            repository.initialize();
            assertAll(
                    () -> assertEquals(
                            RewardPlanStatus.UNKNOWN,
                            repository.rewardPlan(PLAN_ID)
                                    .get(5, TimeUnit.SECONDS)
                                    .orElseThrow()
                                    .status()),
                    () -> assertTrue(repository.recentRuns(100).stream()
                            .anyMatch(run -> run.id().equals(protectedRunId))));
        }
    }

    @Test
    void retentionPreservesUnknownRunsForCrashRecoveryOwnership() throws Exception {
        Path database = temporaryDirectory.resolve("retention-unknown-run.db");
        UUID interruptedRun = UUID.fromString("50000000-0000-0000-0000-000000000091");
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database), logger, 1)) {
            repository.initialize();
            repository.startRun(new RunStart(
                            interruptedRun,
                            ALICE_UUID,
                            "Alice",
                            "main",
                            BASE_TIME,
                            "2.1.0-003"))
                    .get(5, TimeUnit.SECONDS);
            repository.recordCompletedRun(completedRun(
                            UUID.fromString("50000000-0000-0000-0000-000000000092"),
                            BOB_UUID,
                            21))
                    .get(5, TimeUnit.SECONDS);
            repository.recordCompletedRun(completedRun(
                            UUID.fromString("50000000-0000-0000-0000-000000000093"),
                            BOB_UUID,
                            22))
                    .get(5, TimeUnit.SECONDS);
        }

        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database), logger, 1)) {
            repository.initialize();
            assertAll(
                    () -> assertEquals(2L, repository.durabilityMetrics().retainedRuns()),
                    () -> assertTrue(repository.recentRuns(100).stream().anyMatch(run ->
                            run.id().equals(interruptedRun)
                                    && run.status() == RunStatus.UNKNOWN)),
                    () -> assertTrue(repository.recentRuns(100).stream().anyMatch(run ->
                            run.id().equals(UUID.fromString(
                                    "50000000-0000-0000-0000-000000000093")))));
        }
    }

    @Test
    void automaticRetentionPrunesOldRunsAndTheirRewardLedgers() throws Exception {
        Path database = temporaryDirectory.resolve("retention.db");
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        UUID oldRunId = new UUID(0x4000000000000000L, 1L);
        RewardPlanRequest oldRequest = new RewardPlanRequest(
                PLAN_ID,
                oldRunId,
                "old-run",
                BASE_TIME.plusSeconds(10),
                List.of());
        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database), logger, 2)) {
            repository.initialize();
            for (int index = 0; index < 3; index++) {
                UUID runId = new UUID(0x4000000000000000L, index + 1L);
                repository.recordCompletedRun(completedRun(runId, ALICE_UUID, 10 + index))
                        .get(5, TimeUnit.SECONDS);
                if (index == 0) {
                    repository.prepareRewardPlan(oldRequest)
                            .get(5, TimeUnit.SECONDS);
                }
            }
            ExecutionException replay = assertThrows(
                    ExecutionException.class,
                    () -> repository.prepareRewardPlan(oldRequest).get(5, TimeUnit.SECONDS));
            assertAll(
                    () -> assertEquals(2, repository.recentRuns(100).size()),
                    () -> assertEquals(2L, repository.durabilityMetrics().retainedRuns()),
                    () -> assertTrue(repository.rewardPlan(PLAN_ID)
                            .get(5, TimeUnit.SECONDS)
                            .isEmpty()),
                    () -> assertTrue(replay.getCause() instanceof ScoreRepositoryException),
                    () -> assertTrue(replay.getCause().getMessage().contains("replay")));
        }
        try (Connection connection = connect(database)) {
            assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM reward_tombstones"));
        }
    }

    @Test
    void closeDrainsAcceptedMutationsAndRejectsNewOnes() throws Exception {
        Path database = temporaryDirectory.resolve("close.db");
        JdbcScoreRepository repository = repository(database);
        repository.initialize();
        repository.recordScore(BOB_UUID, "Bob", 19).get(5, TimeUnit.SECONDS);

        assertTrue(repository.close(Duration.ofSeconds(2)));
        assertThrows(
                CompletionException.class,
                () -> repository.recordScore(BOB_UUID, "Bob", 20).join());
    }

    @Test
    void exclusiveInstanceLockPreventsTwoRepositoriesSharingOneDatabase() throws Exception {
        Path database = temporaryDirectory.resolve("exclusive-instance.db");
        JdbcScoreRepository first = repository(database);
        JdbcScoreRepository competing = repository(database);
        first.initialize();
        try {
            ScoreRepositoryException failure = assertThrows(
                    ScoreRepositoryException.class,
                    competing::initialize);
            assertTrue(failure.getMessage().contains("already owns"));
        } finally {
            assertTrue(first.close(Duration.ofSeconds(2)));
            competing.close(Duration.ofSeconds(2));
        }

        try (JdbcScoreRepository replacement = repository(database)) {
            replacement.initialize();
            assertEquals(0, replacement.snapshot().totalEntries());
        }
    }

    @Test
    void instanceLockRefusesSymlinkWithoutTouchingItsTarget() throws Exception {
        Path database = temporaryDirectory.resolve("lock-symlink.db");
        Path external = temporaryDirectory.resolve("operator-owned-file");
        Path lock = temporaryDirectory.resolve("lock-symlink.db.walktheplank.lock");
        Files.writeString(external, "do-not-change");
        Files.createSymbolicLink(lock, external);
        JdbcScoreRepository repository = repository(database);
        try {
            assertThrows(ScoreRepositoryException.class, repository::initialize);
            assertEquals("do-not-change", Files.readString(external));
        } finally {
            repository.close(Duration.ofSeconds(2));
        }
    }

    @Test
    void closeDrainsFifoInterruptionQueuedImmediatelyAfterRunStart() throws Exception {
        Path database = temporaryDirectory.resolve("close-pending-start.db");
        UUID runId = UUID.fromString("40000000-0000-0000-0000-000000000099");
        JdbcScoreRepository repository = repository(database);
        repository.initialize();

        CompletableFuture<RunRecord> started = repository.startRun(new RunStart(
                runId,
                ALICE_UUID,
                "Alice",
                "main",
                BASE_TIME,
                "2.1.0-003"));
        CompletableFuture<RunRecord> interrupted = repository.markRunInterrupted(
                runId,
                RunStatus.ABORTED,
                BASE_TIME.plusSeconds(1),
                "SHUTDOWN");

        assertAll(
                () -> assertTrue(repository.close(Duration.ofSeconds(2))),
                () -> assertEquals(RunStatus.STARTED, started.get(1, TimeUnit.SECONDS).status()),
                () -> assertEquals(RunStatus.ABORTED, interrupted.get(1, TimeUnit.SECONDS).status()),
                () -> assertEquals(RunStatus.ABORTED, repository.recentRuns(1).getFirst().status()),
                () -> assertEquals(
                        Optional.of("SHUTDOWN"),
                        repository.recentRuns(1).getFirst().endReason()));
    }

    @Test
    void closeTimeoutRejectsQueuedFuturesAndClearsPendingMetrics() throws Exception {
        Path database = temporaryDirectory.resolve("close-timeout.db");
        RetryLogHandler logs = new RetryLogHandler();
        JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database, Duration.ofMillis(1)), logger(logs));
        repository.initialize();

        try (Connection blocker = connect(database)) {
            holdWriteLock(blocker);
            CompletableFuture<ScoreUpdateResult> running =
                    repository.recordScore(ALICE_UUID, "Alice", 31);
            CompletableFuture<ScoreUpdateResult> queued =
                    repository.recordScore(BOB_UUID, "Bob", 19);
            assertTrue(logs.firstRetry.await(2, TimeUnit.SECONDS));

            assertFalse(repository.close(Duration.ZERO));
            assertThrows(
                    ExecutionException.class,
                    () -> running.get(2, TimeUnit.SECONDS));
            assertThrows(
                    ExecutionException.class,
                    () -> queued.get(2, TimeUnit.SECONDS));
            assertEquals(0, repository.durabilityMetrics().pendingMutations());
            blocker.rollback();
        } finally {
            repository.close(Duration.ofSeconds(1));
        }
    }

    @Test
    void retriesTransientSQLiteLockAndReportsRecoveryAttempt() throws Exception {
        Path database = temporaryDirectory.resolve("retry-recovery.db");
        RetryLogHandler logs = new RetryLogHandler();
        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database, Duration.ofMillis(1)), logger(logs))) {
            repository.initialize();

            try (Connection blocker = connect(database)) {
                holdWriteLock(blocker);
                CompletableFuture<ScoreUpdateResult> write =
                        repository.recordScore(ALICE_UUID, "Alice", 31);
                try {
                    assertTrue(logs.firstRetry.await(2, TimeUnit.SECONDS));
                } finally {
                    blocker.rollback();
                }

                ScoreUpdateResult result = write.get(5, TimeUnit.SECONDS);
                assertAll(
                        () -> assertTrue(result.created()),
                        () -> assertTrue(result.newBest()),
                        () -> assertEquals(31, repository.stats(ALICE_UUID)
                                .orElseThrow()
                                .bestScore()),
                        () -> assertTrue(logs.retryWarnings() >= 1));
            }
        }
    }

    @Test
    void stopsAfterThreeTransientWriteAttemptsAndKeepsWriterUsable() throws Exception {
        Path database = temporaryDirectory.resolve("retry-bound.db");
        RetryLogHandler logs = new RetryLogHandler();
        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database, Duration.ofMillis(1)), logger(logs))) {
            repository.initialize();

            try (Connection blocker = connect(database)) {
                holdWriteLock(blocker);
                CompletableFuture<ScoreUpdateResult> write =
                        repository.recordScore(BOB_UUID, "Bob", 19);
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> write.get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof ScoreRepositoryException);
                blocker.rollback();
            }

            assertAll(
                    () -> assertEquals(2, logs.retryWarnings()),
                    () -> assertEquals(1, logs.exhaustedErrors()),
                    () -> assertTrue(repository.stats(BOB_UUID).isEmpty()));

            ScoreUpdateResult recovered = repository.recordScore(BOB_UUID, "Bob", 19)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(recovered.created());
        }
    }

    @Test
    void retriesOnlyClassifiedTransientDatabaseFailures() {
        assertAll(
                () -> assertTrue(JdbcScoreRepository.isRetryableDatabaseFailure(
                        new SQLException("[SQLITE_BUSY] database is locked", null, 5))),
                () -> assertTrue(JdbcScoreRepository.isRetryableDatabaseFailure(
                        new SQLException("[SQLITE_BUSY_SNAPSHOT] retry transaction", null, 517))),
                () -> assertFalse(JdbcScoreRepository.isRetryableDatabaseFailure(
                        new SQLIntegrityConstraintViolationException("duplicate key"))));
    }

    private static JdbcScoreRepository repository(Path database) {
        return new JdbcScoreRepository(DatabaseSettings.sqlite(database));
    }

    private static CompletedRun completedRun(UUID runId, UUID playerId, int score) {
        return new CompletedRun(
                runId,
                playerId,
                playerId.equals(ALICE_UUID) ? "Alice" : "Bob",
                "main",
                BASE_TIME.plusSeconds(score),
                BASE_TIME.plusSeconds(score + 5L),
                score,
                "FELL",
                "2.1.0-003",
                Optional.empty());
    }

    private static RewardPlanRequest rewardRequest(
            UUID planId,
            UUID runId,
            String idempotencyKey) {
        return new RewardPlanRequest(
                planId,
                runId,
                idempotencyKey,
                BASE_TIME.plusSeconds(10),
                List.of(
                        new RewardStepSpec("give", "a".repeat(64)),
                        new RewardStepSpec("cmi", "b".repeat(64))));
    }

    private static Logger logger(Handler handler) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
    }

    private static void assertSeasonTransitionRejected(CompletableFuture<Season> transition) {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> transition.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof ScoreRepositoryException);
    }

    private static void holdWriteLock(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO scoreboard (score, username) VALUES (999, 'LockHolder')");
        }
    }

    private static Connection connect(Path database) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void createLiveDatabase(Path database) throws SQLException {
        createIdentityDatabase(database, List.of(
                new LegacyIdentity(62L, 149, ALICE_UUID.toString(), "Alice"),
                new LegacyIdentity(57L, 131, BOB_UUID.toString(), "Bob")));
    }

    private static void createLegacyScoreboard(Path database, long score, String username)
            throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE scoreboard (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        score INTEGER NOT NULL DEFAULT 0,
                        username VARCHAR(128) NOT NULL
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO scoreboard (score, username) VALUES (?, ?)")) {
                insert.setLong(1, score);
                insert.setString(2, username);
                insert.executeUpdate();
            }
        }
    }

    private static void createIdentityDatabase(Path database, List<LegacyIdentity> rows)
            throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE scoreboard (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        score INTEGER NOT NULL DEFAULT 0,
                        uuid VARCHAR(128) NOT NULL,
                        username VARCHAR(128) NOT NULL
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO scoreboard (id, score, uuid, username) VALUES (?, ?, ?, ?)")) {
                for (LegacyIdentity row : rows) {
                    insert.setLong(1, row.id());
                    insert.setInt(2, row.score());
                    insert.setString(3, row.uuid());
                    insert.setString(4, row.username());
                    insert.executeUpdate();
                }
            }
        }
    }

    private static void assertSQLiteQuickCheck(Path database) throws SQLException {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            assertTrue(result.next());
            assertEquals("ok", result.getString(1));
            assertFalse(result.next());
        }
    }

    private static int pragmaInt(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static boolean columnIsNullable(
            Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ')')) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return result.getInt("notnull") == 0;
                }
            }
        }
        throw new AssertionError("Missing column " + column);
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(Path database, String table, String column)
            throws SQLException {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ')')) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Set<String> repositoryIndexes(Connection connection) throws SQLException {
        Set<String> indexes = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA index_list(scoreboard)")) {
            while (result.next()) {
                String name = result.getString("name");
                if (name.startsWith("idx_scoreboard_") || name.startsWith("uq_scoreboard_")) {
                    indexes.add(name);
                }
            }
        }
        return Set.copyOf(indexes);
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String queryString(Path database, String sql) throws SQLException {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            String value = result.getString(1);
            assertNotNull(value);
            return value;
        }
    }

    private record LegacyIdentity(long id, int score, String uuid, String username) {
    }

    private static final class RetryLogHandler extends Handler {
        private final CountDownLatch firstRetry = new CountDownLatch(1);
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
            if (record.getLevel() == Level.WARNING
                    && record.getMessage().contains("retrying in")) {
                firstRetry.countDown();
            }
        }

        private long retryWarnings() {
            return records.stream()
                    .filter(record -> record.getLevel() == Level.WARNING)
                    .filter(record -> record.getMessage().contains("retrying in"))
                    .count();
        }

        private long exhaustedErrors() {
            return records.stream()
                    .filter(record -> record.getLevel() == Level.SEVERE)
                    .filter(record -> record.getMessage().contains("no further retries"))
                    .count();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
