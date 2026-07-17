package com.mrfdev.walktheplank.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunInvestigationQueryTest {
    private static final Instant BASE_TIME = Instant.parse("2026-07-14T10:00:00Z");
    private static final UUID ALICE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SEASON = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ONE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID RUN_TWO = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID RUN_THREE = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID PLAN = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final String INJECTION = "main' OR 1=1 --";

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesResultLimitTextAndCompleteBoundedTimeWindow() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> RunInvestigationQuery.all(0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> RunInvestigationQuery.all(101)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> queryWithWindow(
                                Optional.of(BASE_TIME), Optional.empty(), 20)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> queryWithWindow(
                                Optional.of(BASE_TIME),
                                Optional.of(BASE_TIME),
                                20)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> queryWithWindow(
                                Optional.of(BASE_TIME),
                                Optional.of(BASE_TIME.plus(Duration.ofDays(367))),
                                20)),
                () -> assertEquals(
                        100,
                        queryWithWindow(
                                        Optional.of(BASE_TIME),
                                        Optional.of(BASE_TIME.plus(Duration.ofDays(366))),
                                        100)
                                .limit()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> RunInvestigationQuery.forArena(" ", 20)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> RunInvestigationQuery.forArena("bad\nvalue", 20)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> RunInvestigationQuery.forArena("a".repeat(129), 20)));
    }

    @Test
    void queryAndResultDtosExposeNoPlayerNameOwnershipField() {
        List<String> queryFields = Arrays.stream(RunInvestigationQuery.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
                .toList();
        List<String> resultFields = Arrays.stream(RunInvestigationRecord.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
                .toList();

        assertAll(
                () -> assertFalse(queryFields.contains("username")),
                () -> assertFalse(queryFields.contains("playername")),
                () -> assertFalse(resultFields.contains("username")),
                () -> assertFalse(resultFields.contains("playername")),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RunInvestigationRecord(
                                1,
                                RUN_ONE,
                                ALICE,
                                "main",
                                BASE_TIME,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                "release",
                                Optional.empty(),
                                RunStatus.STARTED,
                                Optional.of(PLAN),
                                Optional.empty())));
    }

    @Test
    void preparedFiltersAreExactOrderedBoundedAndIncludeRedactedPlanState() throws Exception {
        Path database = temporaryDirectory.resolve("investigation.db");
        try (JdbcScoreRepository repository = new JdbcScoreRepository(
                DatabaseSettings.sqlite(database))) {
            repository.initialize();
            seedRuns(repository);

            List<RunInvestigationRecord> all = investigate(
                    repository, RunInvestigationQuery.all(100));
            List<RunInvestigationRecord> completed = investigate(
                    repository, RunInvestigationQuery.forStatus(RunStatus.COMPLETED, 100));
            List<RunInvestigationRecord> alice = investigate(
                    repository, RunInvestigationQuery.forPlayer(ALICE, 100));
            List<RunInvestigationRecord> injectedArena = investigate(
                    repository, RunInvestigationQuery.forArena(INJECTION, 100));
            List<RunInvestigationRecord> injectedRelease = investigate(
                    repository,
                    RunInvestigationQuery.filtered(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(INJECTION),
                            Optional.empty(),
                            Optional.empty(),
                            100));
            List<RunInvestigationRecord> season = investigate(
                    repository, RunInvestigationQuery.forSeason(SEASON, 100));
            List<RunInvestigationRecord> window = investigate(
                    repository,
                    queryWithWindow(
                            Optional.of(BASE_TIME.plusSeconds(60)),
                            Optional.of(BASE_TIME.plusSeconds(180)),
                            100));
            List<RunInvestigationRecord> limited = investigate(
                    repository, RunInvestigationQuery.all(2));
            List<RunInvestigationRecord> combined = investigate(
                    repository,
                    RunInvestigationQuery.filtered(
                            Optional.of(RunStatus.COMPLETED),
                            Optional.of(ALICE),
                            Optional.of(RUN_ONE),
                            Optional.of("main"),
                            Optional.of(SEASON),
                            Optional.of("release-a"),
                            Optional.of(BASE_TIME.minusSeconds(1)),
                            Optional.of(BASE_TIME.plusSeconds(1)),
                            100));
            Optional<RunInvestigationRecord> exact = repository.run(RUN_ONE)
                    .get(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals(
                            List.of(RUN_THREE, RUN_TWO, RUN_ONE),
                            runIds(all)),
                    () -> assertEquals(List.of(RUN_THREE, RUN_ONE), runIds(completed)),
                    () -> assertEquals(List.of(RUN_THREE, RUN_ONE), runIds(alice)),
                    () -> assertEquals(List.of(RUN_TWO), runIds(injectedArena)),
                    () -> assertEquals(List.of(RUN_TWO), runIds(injectedRelease)),
                    () -> assertEquals(List.of(RUN_TWO, RUN_ONE), runIds(season)),
                    () -> assertEquals(List.of(RUN_THREE, RUN_TWO), runIds(window)),
                    () -> assertEquals(List.of(RUN_THREE, RUN_TWO), runIds(limited)),
                    () -> assertEquals(List.of(RUN_ONE), runIds(combined)),
                    () -> assertEquals(RUN_ONE, exact.orElseThrow().runId()),
                    () -> assertEquals(ALICE, exact.orElseThrow().playerId()),
                    () -> assertEquals(Optional.of(PLAN), exact.orElseThrow().rewardPlanId()),
                    () -> assertEquals(
                            Optional.of(RewardPlanStatus.PENDING),
                            exact.orElseThrow().rewardPlanStatus()),
                    () -> assertTrue(repository.run(UUID.randomUUID())
                            .get(5, TimeUnit.SECONDS)
                            .isEmpty()));
        }
    }

    private static void seedRuns(JdbcScoreRepository repository) throws Exception {
        repository.createSeason(SEASON, "Summer", BASE_TIME.minusSeconds(60))
                .get(5, TimeUnit.SECONDS);
        repository.activateSeason(SEASON, BASE_TIME.minusSeconds(30))
                .get(5, TimeUnit.SECONDS);
        repository.startRun(new RunStart(
                        RUN_ONE, ALICE, "Alice", "main", BASE_TIME, "release-a"))
                .get(5, TimeUnit.SECONDS);
        RunCompletion firstCompletion = new RunCompletion(
                RUN_ONE, BASE_TIME.plusSeconds(10), 10, "FELL");
        repository.completeRunWithRewardPlan(
                        firstCompletion,
                        Optional.of(new RewardPlanRequest(
                                PLAN,
                                RUN_ONE,
                                "run:" + RUN_ONE,
                                firstCompletion.endedAt(),
                                List.of(new RewardStepSpec("say", "a".repeat(64))))))
                .get(5, TimeUnit.SECONDS);

        repository.startRun(new RunStart(
                        RUN_TWO,
                        BOB,
                        "Bob",
                        INJECTION,
                        BASE_TIME.plusSeconds(60),
                        INJECTION))
                .get(5, TimeUnit.SECONDS);
        repository.markRunInterrupted(
                        RUN_TWO,
                        RunStatus.ABORTED,
                        BASE_TIME.plusSeconds(70),
                        "ADMIN")
                .get(5, TimeUnit.SECONDS);
        repository.closeSeason(SEASON, BASE_TIME.plusSeconds(80))
                .get(5, TimeUnit.SECONDS);

        repository.startRun(new RunStart(
                        RUN_THREE,
                        ALICE,
                        "AliceNew",
                        "secondary",
                        BASE_TIME.plusSeconds(120),
                        "release-b"))
                .get(5, TimeUnit.SECONDS);
        repository.completeRun(new RunCompletion(
                        RUN_THREE, BASE_TIME.plusSeconds(130), 30, "FELL"))
                .get(5, TimeUnit.SECONDS);
    }

    private static RunInvestigationQuery queryWithWindow(
            Optional<Instant> start,
            Optional<Instant> end,
            int limit) {
        return RunInvestigationQuery.filtered(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                start,
                end,
                limit);
    }

    private static List<RunInvestigationRecord> investigate(
            JdbcScoreRepository repository,
            RunInvestigationQuery query) throws Exception {
        return repository.investigateRuns(query).get(5, TimeUnit.SECONDS);
    }

    private static List<UUID> runIds(List<RunInvestigationRecord> runs) {
        return runs.stream().map(RunInvestigationRecord::runId).toList();
    }
}
