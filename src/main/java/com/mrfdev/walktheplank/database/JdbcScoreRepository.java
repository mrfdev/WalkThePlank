package com.mrfdev.walktheplank.database;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQLite JDBC-backed implementation of the score repository. */
public final class JdbcScoreRepository implements ScoreRepository {
    private static final int SCHEMA_VERSION = 3;
    private static final int MAX_MUTATION_ATTEMPTS = 3;
    private static final int DEFAULT_RUN_HISTORY_LIMIT = 10_000;
    private static final int MAX_RUN_HISTORY_LIMIT = 1_000_000;
    private static final int RECENT_RUN_CACHE_LIMIT = 100;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 50L;
    private static final String UUID_INDEX = "uq_scoreboard_uuid";
    private static final String RANKING_INDEX = "idx_scoreboard_ranking";
    private static final String ACTIVE_SEASON_INDEX = "uq_seasons_active";
    private static final String SEASON_RANKING_INDEX = "idx_season_scores_ranking";
    private static final String RUN_RECENT_INDEX = "idx_run_history_recent";
    private static final String RUN_PLAYER_INDEX = "idx_run_history_player";
    private static final String REWARD_PLAN_STATUS_INDEX = "idx_reward_plans_status";
    private static final String REWARD_STEP_STATUS_INDEX = "idx_reward_steps_status";
    private static final String CATEGORY_RANKING_INDEX = "idx_category_scores_ranking";
    private static final String SQLITE_TRIMMED_UUID =
            "trim(uuid, char(9) || char(10) || char(11) || char(12) || char(13) || char(32))";
    private static final String SQLITE_RESOLVED_UUID =
            "uuid IS NOT NULL AND " + SQLITE_TRIMMED_UUID + " <> ''";
    private static final String SQLITE_IDENTITY_PREDICATE =
            "uuid = ? COLLATE NOCASE AND " + SQLITE_RESOLVED_UUID;

    private final DatabaseSettings settings;
    private final Logger logger;
    private final int runHistoryRetentionLimit;
    private final Object lifecycleMonitor = new Object();
    private final ExecutorService mutationExecutor;
    private final AtomicInteger pendingMutationCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<PendingMutation> pendingMutations =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RepositoryOperation<?>> acceptedOperations =
            new ConcurrentLinkedQueue<>();

    private volatile ScoreSnapshot snapshot = ScoreSnapshot.empty();
    private volatile DurabilityState durabilityState = DurabilityState.empty();
    private volatile Optional<Path> migrationBackup = Optional.empty();
    private volatile int migrationBackupsPrunedAtStartup;
    private volatile boolean initialized;
    private volatile boolean closed;
    private volatile Thread mutationThread;
    private volatile Instant lastSuccessfulWriteAt;
    private volatile SanitizedDatabaseFailure lastDatabaseFailure;
    private FileChannel instanceLockChannel;
    private FileLock instanceLock;

    public JdbcScoreRepository(DatabaseSettings settings) {
        this(
                settings,
                Logger.getLogger(JdbcScoreRepository.class.getName()),
                DEFAULT_RUN_HISTORY_LIMIT);
    }

    public JdbcScoreRepository(DatabaseSettings settings, Logger logger) {
        this(settings, logger, DEFAULT_RUN_HISTORY_LIMIT);
    }

    public JdbcScoreRepository(
            DatabaseSettings settings,
            Logger logger,
            int runHistoryRetentionLimit) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (runHistoryRetentionLimit < 1
                || runHistoryRetentionLimit > MAX_RUN_HISTORY_LIMIT) {
            throw new IllegalArgumentException(
                    "runHistoryRetentionLimit must be between 1 and "
                            + MAX_RUN_HISTORY_LIMIT);
        }
        this.runHistoryRetentionLimit = runHistoryRetentionLimit;
        this.mutationExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = Thread.ofPlatform()
                    .name("walktheplank-score-writer")
                    .daemon(true)
                    .unstarted(task);
            mutationThread = thread;
            return thread;
        });
    }

    @Override
    public void initialize() throws ScoreRepositoryException {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new ScoreRepositoryException("The score repository is already closed");
            }
            if (initialized) {
                return;
            }

            acquireInstanceLock();
            try {
                initializeSQLite();

                try (Connection connection = openConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        recoverInterruptedRuns(connection);
                        recoverInterruptedRewardPlans(connection);
                        pruneRunHistory(connection, runHistoryRetentionLimit);
                        ScoreSnapshot initialScores = loadSnapshot(connection);
                        DurabilityState initialDurability = loadDurabilityState(connection);
                        connection.commit();
                        snapshot = initialScores;
                        durabilityState = initialDurability;
                    } catch (SQLException | ScoreRepositoryException exception) {
                        rollback(connection, exception);
                        throw exception;
                    }
                } catch (SQLException exception) {
                    throw new ScoreRepositoryException(
                            "Could not load the initial score snapshot", exception);
                }
                initialized = true;
            } catch (ScoreRepositoryException | RuntimeException | Error failure) {
                releaseInstanceLock();
                throw failure;
            }
        }
    }

    @Override
    public CompletableFuture<Void> touchIdentity(UUID uuid, String username) {
        UUID checkedUuid = Objects.requireNonNull(uuid, "uuid");
        String checkedUsername = validateUsername(username);
        return submitMutation(() -> {
            mutateWithRetry("identity refresh", checkedUuid, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE scoreboard SET username = ?, updated_at = CURRENT_TIMESTAMP WHERE "
                                + SQLITE_IDENTITY_PREDICATE)) {
                    statement.setString(1, checkedUsername);
                    statement.setString(2, checkedUuid.toString());
                    statement.executeUpdate();
                }
                return null;
            });
            return null;
        });
    }

    @Override
    public CompletableFuture<ScoreUpdateResult> recordScore(UUID uuid, String username, int score) {
        UUID checkedUuid = Objects.requireNonNull(uuid, "uuid");
        String checkedUsername = validateUsername(username);
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative");
        }

        return submitMutation(() -> mutateWithRetry("score write", checkedUuid, connection -> {
            ExistingScore existing = findExistingScore(connection, checkedUuid);
            if (existing == null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO scoreboard (score, uuid, username, updated_at) "
                                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
                    statement.setInt(1, score);
                    statement.setString(2, checkedUuid.toString());
                    statement.setString(3, checkedUsername);
                    statement.executeUpdate();
                }
                return new ScoreUpdateResult(
                        checkedUuid,
                        checkedUsername,
                        score,
                        0,
                        score,
                        score > 0,
                        true);
            }

            int bestScore = Math.max(existing.score(), score);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE scoreboard SET score = ?, username = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ?")) {
                statement.setInt(1, bestScore);
                statement.setString(2, checkedUsername);
                statement.setLong(3, existing.id());
                statement.executeUpdate();
            }
            return new ScoreUpdateResult(
                    checkedUuid,
                    checkedUsername,
                    score,
                    existing.score(),
                    bestScore,
                    score > existing.score(),
                    false);
        }));
    }

    @Override
    public CompletableFuture<CompletedRunResult> recordCompletedRun(CompletedRun run) {
        CompletedRun checkedRun = Objects.requireNonNull(run, "run");
        return submitMutation(() -> mutateWithRetry(
                "completed run write",
                checkedRun.id(),
                connection -> recordCompletedRun(connection, checkedRun)));
    }

    @Override
    public CompletableFuture<RunRecord> startRun(RunStart run) {
        RunStart checkedRun = Objects.requireNonNull(run, "run");
        return submitMutation(() -> mutateWithRetry(
                "run start",
                checkedRun.id(),
                connection -> startRun(connection, checkedRun, Optional.empty())));
    }

    @Override
    public CompletableFuture<CompletedRunResult> completeRun(RunCompletion completion) {
        RunCompletion checkedCompletion = Objects.requireNonNull(completion, "completion");
        return submitMutation(() -> mutateWithRetry(
                "run completion",
                checkedCompletion.runId(),
                connection -> completeRun(connection, checkedCompletion)));
    }

    @Override
    public CompletableFuture<CompletedRunWithRewardPlanResult> completeRunWithRewardPlan(
            RunCompletion completion,
            Optional<RewardPlanRequest> rewardPlan) {
        RunCompletion checkedCompletion = Objects.requireNonNull(completion, "completion");
        Optional<RewardPlanRequest> checkedRewardPlan = Objects.requireNonNull(
                rewardPlan, "rewardPlan");
        checkedRewardPlan.ifPresent(plan -> {
            if (!plan.runId().equals(checkedCompletion.runId())) {
                throw new IllegalArgumentException(
                        "rewardPlan.runId must match completion.runId");
            }
            if (!sameStoredInstant(plan.createdAt(), checkedCompletion.endedAt())) {
                throw new IllegalArgumentException(
                        "rewardPlan.createdAt must equal completion.endedAt");
            }
            if (plan.steps().isEmpty()) {
                throw new IllegalArgumentException(
                        "An atomic reward plan must contain at least one redacted step");
            }
        });
        return submitMutation(() -> mutateWithRetry(
                "run completion with reward intent",
                checkedCompletion.runId(),
                connection -> completeRunWithRewardPlan(
                        connection, checkedCompletion, checkedRewardPlan)));
    }

    @Override
    public CompletableFuture<RunRecord> markRunInterrupted(
            UUID runId,
            RunStatus status,
            Instant endedAt,
            String reason) {
        UUID checkedId = Objects.requireNonNull(runId, "runId");
        RunStatus checkedStatus = requireInterruptedRunStatus(status);
        Instant checkedEnd = PersistenceValidation.instant(endedAt, "endedAt");
        String checkedReason = PersistenceValidation.text(reason, "reason", 64);
        return submitMutation(() -> mutateWithRetry(
                "run interruption",
                checkedId,
                connection -> markRunInterrupted(
                        connection, checkedId, checkedStatus, checkedEnd, checkedReason)));
    }

    @Override
    public CompletableFuture<Season> createSeason(UUID id, String name, Instant createdAt) {
        Season requested = new Season(
                Objects.requireNonNull(id, "id"),
                name,
                SeasonStatus.PLANNED,
                createdAt,
                createdAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return submitMutation(() -> mutateWithRetry(
                "season creation",
                requested.id(),
                connection -> createSeason(connection, requested)));
    }

    @Override
    public CompletableFuture<Season> activateSeason(UUID id, Instant activatedAt) {
        UUID checkedId = Objects.requireNonNull(id, "id");
        Instant checkedTime = PersistenceValidation.instant(activatedAt, "activatedAt");
        return submitMutation(() -> mutateWithRetry(
                "season activation",
                checkedId,
                connection -> activateSeason(connection, checkedId, checkedTime)));
    }

    @Override
    public CompletableFuture<Season> closeSeason(UUID id, Instant closedAt) {
        UUID checkedId = Objects.requireNonNull(id, "id");
        Instant checkedTime = PersistenceValidation.instant(closedAt, "closedAt");
        return submitMutation(() -> mutateWithRetry(
                "season closure",
                checkedId,
                connection -> closeSeason(connection, checkedId, checkedTime)));
    }

    @Override
    public CompletableFuture<Season> reopenSeason(UUID id, Instant reopenedAt) {
        UUID checkedId = Objects.requireNonNull(id, "id");
        Instant checkedTime = PersistenceValidation.instant(reopenedAt, "reopenedAt");
        return submitMutation(() -> mutateWithRetry(
                "season reopen",
                checkedId,
                connection -> reopenSeason(connection, checkedId, checkedTime)));
    }

    @Override
    public CompletableFuture<Season> archiveSeason(UUID id, Instant archivedAt) {
        UUID checkedId = Objects.requireNonNull(id, "id");
        Instant checkedTime = PersistenceValidation.instant(archivedAt, "archivedAt");
        return submitMutation(() -> mutateWithRetry(
                "season archive",
                checkedId,
                connection -> archiveSeason(connection, checkedId, checkedTime)));
    }

    @Override
    public List<Season> seasons() {
        return durabilityState.seasons();
    }

    @Override
    public Optional<Season> activeSeason() {
        return durabilityState.metrics().activeSeason();
    }

    @Override
    public Optional<ScoreSnapshot> activeSeasonScores() {
        return durabilityState.activeSeasonScores();
    }

    @Override
    public CompletableFuture<ScoreSnapshot> seasonScores(UUID seasonId) {
        UUID checkedId = Objects.requireNonNull(seasonId, "seasonId");
        return submitRead(() -> {
            try (Connection connection = openConnection()) {
                if (findSeason(connection, checkedId) == null) {
                    throw new ScoreRepositoryException("Unknown season " + checkedId);
                }
                return loadSeasonSnapshot(connection, checkedId);
            } catch (SQLException exception) {
                throw new ScoreRepositoryException(
                        "Could not load scores for season " + checkedId, exception);
            }
        });
    }

    @Override
    public List<RunRecord> recentRuns(int limit) {
        if (limit < 0 || limit > RECENT_RUN_CACHE_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and " + RECENT_RUN_CACHE_LIMIT);
        }
        List<RunRecord> cached = durabilityState.recentRuns();
        return cached.subList(0, Math.min(limit, cached.size()));
    }

    @Override
    public CompletableFuture<List<RunInvestigationRecord>> investigateRuns(
            RunInvestigationQuery query) {
        RunInvestigationQuery checkedQuery = Objects.requireNonNull(query, "query");
        return submitRead(() -> {
            try (Connection connection = openConnection()) {
                return loadInvestigatedRuns(connection, checkedQuery);
            } catch (SQLException exception) {
                throw new ScoreRepositoryException(
                        "Could not load bounded retained-run evidence", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<RunInvestigationRecord>> run(UUID runId) {
        UUID checkedId = Objects.requireNonNull(runId, "runId");
        return investigateRuns(RunInvestigationQuery.forRun(checkedId))
                .thenApply(runs -> runs.stream().findFirst());
    }

    @Override
    public int runHistoryRetentionLimit() {
        return runHistoryRetentionLimit;
    }

    @Override
    public CompletableFuture<RetentionResult> pruneRunHistory(int retainMostRecent) {
        validateRetentionLimit(retainMostRecent);
        return submitMutation(() -> mutateWithRetry(
                "run history pruning",
                "retention=" + retainMostRecent,
                connection -> pruneRunHistory(connection, retainMostRecent)));
    }

    @Override
    public CompletableFuture<RewardPlanBeginResult> prepareRewardPlan(RewardPlanRequest request) {
        RewardPlanRequest checkedRequest = Objects.requireNonNull(request, "request");
        return submitMutation(() -> mutateWithRetry(
                "reward plan creation",
                checkedRequest.planId(),
                connection -> beginRewardPlan(connection, checkedRequest)));
    }

    @Override
    public CompletableFuture<RewardStepDispatchResult> claimRewardStep(
            UUID planId,
            int stepIndex,
            Instant attemptedAt) {
        UUID checkedPlanId = Objects.requireNonNull(planId, "planId");
        validateStepIndex(stepIndex);
        Instant checkedTime = PersistenceValidation.instant(attemptedAt, "attemptedAt");
        return submitMutation(() -> mutateWithRetry(
                "reward step dispatch marker",
                checkedPlanId,
                connection -> markRewardStepDispatching(
                        connection, checkedPlanId, stepIndex, checkedTime)));
    }

    @Override
    public CompletableFuture<RewardPlanRecord> recordRewardStepOutcome(
            UUID planId,
            int stepIndex,
            RewardStepStatus outcome,
            Instant completedAt) {
        UUID checkedPlanId = Objects.requireNonNull(planId, "planId");
        validateStepIndex(stepIndex);
        RewardStepStatus checkedOutcome = requireTerminalOutcome(outcome);
        Instant checkedTime = PersistenceValidation.instant(completedAt, "completedAt");
        return submitMutation(() -> mutateWithRetry(
                "reward step completion",
                checkedPlanId,
                connection -> completeRewardStep(
                        connection,
                        checkedPlanId,
                        stepIndex,
                        checkedOutcome,
                        checkedTime)));
    }

    @Override
    public CompletableFuture<RewardPlanRecord> resolveUnknownRewardStep(
            UUID planId,
            int stepIndex,
            RewardStepStatus resolution,
            Instant resolvedAt) {
        UUID checkedPlanId = Objects.requireNonNull(planId, "planId");
        validateStepIndex(stepIndex);
        RewardStepStatus checkedResolution = requireUnknownResolution(resolution);
        Instant checkedTime = PersistenceValidation.instant(resolvedAt, "resolvedAt");
        return submitMutation(() -> mutateWithRetry(
                "unknown reward step resolution",
                checkedPlanId,
                connection -> resolveUnknownRewardStep(
                        connection,
                        checkedPlanId,
                        stepIndex,
                        checkedResolution,
                        checkedTime)));
    }

    @Override
    public CompletableFuture<RewardPlanRecord> finalizeRewardPlan(
            UUID planId,
            Instant finalizedAt) {
        UUID checkedPlanId = Objects.requireNonNull(planId, "planId");
        Instant checkedTime = PersistenceValidation.instant(finalizedAt, "finalizedAt");
        return submitMutation(() -> mutateWithRetry(
                "reward plan finalization",
                checkedPlanId,
                connection -> finalizeRewardPlan(connection, checkedPlanId, checkedTime)));
    }

    @Override
    public CompletableFuture<Optional<RewardPlanRecord>> rewardPlan(UUID planId) {
        UUID checkedId = Objects.requireNonNull(planId, "planId");
        return submitRead(() -> {
            try (Connection connection = openConnection()) {
                return Optional.ofNullable(findRewardPlan(connection, checkedId));
            } catch (SQLException exception) {
                throw new ScoreRepositoryException(
                        "Could not load reward plan " + checkedId, exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<RewardPlanRecord>> recentRewardPlans(
            RewardPlanStatus status,
            int limit) {
        RewardPlanStatus checkedStatus = Objects.requireNonNull(status, "status");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return submitRead(() -> {
            try (Connection connection = openConnection()) {
                return loadRecentRewardPlans(connection, checkedStatus, limit);
            } catch (SQLException exception) {
                throw new ScoreRepositoryException(
                        "Could not load recent " + checkedStatus + " reward plans", exception);
            }
        });
    }

    @Override
    public DurabilityMetrics durabilityMetrics() {
        DurabilityMetrics durable = durabilityState.metrics();
        PendingMutation oldest = pendingMutations.peek();
        return new DurabilityMetrics(
                durable.capturedAt(),
                durable.retainedRuns(),
                durable.seasonCount(),
                durable.activeSeason(),
                durable.rewardPlansByStatus(),
                durable.rewardStepsByStatus(),
                pendingMutationCount.get(),
                oldest == null ? Optional.empty() : Optional.of(oldest.submittedAt()),
                Optional.ofNullable(lastSuccessfulWriteAt),
                Optional.ofNullable(lastDatabaseFailure));
    }

    @Override
    public CompletableFuture<DatabaseDoctorReport> inspectDatabase() {
        return submitRead(() -> {
            long startedAt = System.nanoTime();
            boolean quickCheckPassed;
            try (Connection connection = openConnection();
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("PRAGMA quick_check(1)")) {
                quickCheckPassed = result.next() && "ok".equalsIgnoreCase(result.getString(1));
            } catch (SQLException exception) {
                throw new ScoreRepositoryException(
                        "Could not complete the read-only SQLite health probe", exception);
            }

            try {
                Path databasePath = settings.databasePath();
                long databaseBytes = regularFileSize(databasePath, true);
                long walBytes = regularFileSize(
                        databasePath.resolveSibling(databasePath.getFileName() + "-wal"),
                        false);
                int migrationBackups = countMigrationBackups(databasePath);
                long latencyMillis = TimeUnit.NANOSECONDS.toMillis(
                        Math.max(0L, System.nanoTime() - startedAt));
                return new DatabaseDoctorReport(
                        quickCheckPassed,
                        databaseBytes,
                        walBytes,
                        migrationBackups,
                        settings.migrationBackupRetention(),
                        migrationBackupsPrunedAtStartup,
                        latencyMillis);
            } catch (IOException exception) {
                throw new ScoreRepositoryException(
                        "Could not inspect SQLite storage metadata", exception);
            }
        });
    }

    @Override
    public ScoreSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public PlayerPreferences preferences(UUID playerId) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        return durabilityState.preferences().getOrDefault(
                checkedId, PlayerPreferences.defaults(checkedId));
    }

    @Override
    public CompletableFuture<PlayerPreferences> updatePreferences(
            PlayerPreferences preferences) {
        PlayerPreferences checked = Objects.requireNonNull(preferences, "preferences");
        return submitMutation(() -> mutateWithRetry(
                "accessibility preference update",
                checked.playerId(),
                connection -> upsertPlayerPreferences(connection, checked)));
    }

    @Override
    public CompletableFuture<PlayerPreferences> updateParticlePreference(
            UUID playerId,
            ParticlePreference preference,
            Instant changedAt) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        ParticlePreference checkedPreference =
                Objects.requireNonNull(preference, "preference");
        Instant checkedChangedAt =
                PersistenceValidation.instant(changedAt, "changedAt");
        return updatePreferenceField(
                checkedId,
                "particle preference update",
                current -> current.withParticles(checkedPreference, checkedChangedAt));
    }

    @Override
    public CompletableFuture<PlayerPreferences> updateSoundPreference(
            UUID playerId,
            boolean enabled,
            Instant changedAt) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        Instant checkedChangedAt =
                PersistenceValidation.instant(changedAt, "changedAt");
        return updatePreferenceField(
                checkedId,
                "sound preference update",
                current -> current.withSounds(enabled, checkedChangedAt));
    }

    @Override
    public CompletableFuture<PlayerPreferences> updateTitlePreference(
            UUID playerId,
            boolean enabled,
            Instant changedAt) {
        UUID checkedId = Objects.requireNonNull(playerId, "playerId");
        Instant checkedChangedAt =
                PersistenceValidation.instant(changedAt, "changedAt");
        return updatePreferenceField(
                checkedId,
                "title preference update",
                current -> current.withTitles(enabled, checkedChangedAt));
    }

    @Override
    public ScoreSnapshot categoryScores(ScoreCategory category) {
        return durabilityState.categoryScores().getOrDefault(
                Objects.requireNonNull(category, "category"),
                ScoreSnapshot.empty());
    }

    @Override
    public Map<ScoreCategory, ScoreSnapshot> categoryScoreSnapshots() {
        return durabilityState.categoryScores();
    }

    @Override
    public Optional<Path> migrationBackup() {
        return migrationBackup;
    }

    @Override
    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }

        synchronized (lifecycleMonitor) {
            if (closed) {
                boolean terminated = mutationExecutor.isTerminated();
                if (terminated) {
                    return releaseInstanceLock();
                }
                return false;
            }
            closed = true;
            mutationExecutor.shutdown();
        }

        if (Thread.currentThread() == mutationThread) {
            rejectQueuedOperations("Repository close was invoked by its worker thread");
            return false;
        }

        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            timeoutNanos = Long.MAX_VALUE;
        }

        try {
            boolean drained = mutationExecutor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!drained) {
                rejectQueuedOperations("Repository close timed out");
                return false;
            }
            return releaseInstanceLock();
        } catch (InterruptedException exception) {
            rejectQueuedOperations("Repository close was interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void acquireInstanceLock() throws ScoreRepositoryException {
        Path databasePath = settings.databasePath();
        Path parent = databasePath.getParent();
        if (parent == null) {
            throw new ScoreRepositoryException("SQLite database must have a parent directory");
        }
        Path lockPath = databasePath.resolveSibling(
                databasePath.getFileName() + ".walktheplank.lock");
        try {
            if (Files.isSymbolicLink(parent)) {
                throw new ScoreRepositoryException(
                        "SQLite parent must not be a symbolic link");
            }
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent)
                    || Files.isSymbolicLink(lockPath)
                    || Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new ScoreRepositoryException(
                        "SQLite instance lock path is not a safe regular file");
            }
            FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            try {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException exception) {
                    throw new ScoreRepositoryException(
                            "Another WalkThePlank instance already owns this SQLite database",
                            exception);
                }
                if (lock == null) {
                    throw new ScoreRepositoryException(
                            "Another WalkThePlank instance already owns this SQLite database");
                }
                instanceLockChannel = channel;
                instanceLock = lock;
            } catch (IOException exception) {
                closeUnownedLockChannel(channel, exception);
                throw new ScoreRepositoryException(
                        "Could not acquire the SQLite instance lock",
                        exception);
            } catch (ScoreRepositoryException exception) {
                closeUnownedLockChannel(channel, exception);
                throw exception;
            }
        } catch (IOException exception) {
            throw new ScoreRepositoryException(
                    "Could not acquire the SQLite instance lock", exception);
        }
    }

    private static void closeUnownedLockChannel(
            FileChannel channel, Exception originalFailure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    private boolean releaseInstanceLock() {
        FileLock lock = instanceLock;
        FileChannel channel = instanceLockChannel;
        instanceLock = null;
        instanceLockChannel = null;
        boolean released = true;
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException exception) {
                released = false;
                logger.log(Level.WARNING, "Could not release the SQLite instance lock", exception);
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException exception) {
                released = false;
                logger.log(Level.WARNING, "Could not close the SQLite instance lock", exception);
            }
        }
        return released;
    }

    private void rejectQueuedOperations(String reason) {
        mutationExecutor.shutdownNow();
        RejectedExecutionException failure = new RejectedExecutionException(reason);
        for (RepositoryOperation<?> operation : acceptedOperations) {
            operation.rejectBeforeStart(failure);
        }
    }

    private void initializeSQLite() throws ScoreRepositoryException {
        Path databasePath = settings.databasePath();
        try {
            Path parent = databasePath.getParent();
            if (parent != null) {
                if (Files.isSymbolicLink(parent)) {
                    throw new ScoreRepositoryException("SQLite parent must not be a symbolic link");
                }
                Files.createDirectories(parent);
            }
            if (parent != null && Files.isSymbolicLink(parent) || Files.isSymbolicLink(databasePath)) {
                throw new ScoreRepositoryException("SQLite path must not use symbolic links");
            }
            if (Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new ScoreRepositoryException("SQLite path is not a regular file: " + databasePath);
            }

            boolean existingDatabase = Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(databasePath) > 0L;
            boolean migrationRequired;
            try (Connection connection = openConnection()) {
                migrationRequired = sqliteMigrationRequired(connection);
            }

            if (migrationRequired && existingDatabase) {
                migrationBackup = Optional.of(createVerifiedSQLiteBackup(databasePath));
            }
            if (migrationRequired) {
                try (Connection connection = openConnection()) {
                    migrateSQLite(connection);
                }
            }
            migrationBackupsPrunedAtStartup = enforceMigrationBackupRetention(databasePath);
        } catch (IOException | SQLException exception) {
            throw new ScoreRepositoryException(
                    "Could not initialize SQLite database at " + databasePath, exception);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + settings.databasePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + settings.busyTimeout().toMillis());
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
        return connection;
    }

    private boolean sqliteMigrationRequired(Connection connection)
            throws SQLException, ScoreRepositoryException {
        int userVersion = sqliteUserVersion(connection);
        if (userVersion > SCHEMA_VERSION) {
            throw new ScoreRepositoryException(
                    "SQLite schema version " + userVersion
                            + " is newer than supported version " + SCHEMA_VERSION);
        }
        if (!sqliteTableExists(connection, "scoreboard")) {
            return true;
        }

        Map<String, ColumnInfo> columns = sqliteColumns(connection, "scoreboard");
        if (!columns.keySet().containsAll(Set.of("id", "score", "username"))) {
            return true;
        }
        validateStoredScoreboardRows(connection);
        if (!columns.containsKey("uuid") || !columns.containsKey("updated_at")) {
            return true;
        }
        if (!columns.get("updated_at").nullable()) {
            throw new ScoreRepositoryException("scoreboard.updated_at must be nullable");
        }
        if (!sqliteTableExists(connection, "schema_migrations")
                || !sqliteMigrationRecorded(connection, SCHEMA_VERSION)
                || !sqliteIndexExists(connection, UUID_INDEX)
                || !sqliteIndexExists(connection, RANKING_INDEX)
                || userVersion != SCHEMA_VERSION) {
            return true;
        }
        requireIndexDefinition(connection, UUID_INDEX, requiredIndexSql().get(UUID_INDEX));
        requireIndexDefinition(connection, RANKING_INDEX, requiredIndexSql().get(RANKING_INDEX));
        if (columns.get("uuid").nullable() && containsBlankUuid(connection)) {
            return true;
        }
        return durabilitySchemaRequiresMigration(connection);
    }

    private void migrateSQLite(Connection connection)
            throws SQLException, ScoreRepositoryException {
        boolean scoreboardExists = sqliteTableExists(connection, "scoreboard");
        Map<String, ColumnInfo> existingColumns = scoreboardExists
                ? sqliteColumns(connection, "scoreboard")
                : Map.of();

        if (scoreboardExists) {
            requireColumns(existingColumns, Set.of("id", "score", "username"));
            validateStoredScoreboardRows(connection);
            if (existingColumns.containsKey("uuid")) {
                validateStoredIdentities(connection);
            }
            ColumnInfo updatedAt = existingColumns.get("updated_at");
            if (updatedAt != null && !updatedAt.nullable()) {
                throw new ScoreRepositoryException("scoreboard.updated_at must be nullable");
            }
        }
        validateExistingDurabilityTables(connection);
        validateExistingIndexDefinitions(connection);

        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            if (!scoreboardExists) {
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
            } else {
                if (!existingColumns.containsKey("uuid")) {
                    statement.executeUpdate(
                            "ALTER TABLE scoreboard ADD COLUMN uuid VARCHAR(36) NULL");
                }
                if (!existingColumns.containsKey("updated_at")) {
                    statement.executeUpdate(
                            "ALTER TABLE scoreboard ADD COLUMN updated_at TIMESTAMP NULL");
                }
            }

            Map<String, ColumnInfo> migratedColumns = sqliteColumns(connection, "scoreboard");
            if (migratedColumns.get("uuid").nullable()) {
                statement.executeUpdate(
                        "UPDATE scoreboard SET uuid = NULL WHERE uuid IS NOT NULL AND "
                                + SQLITE_TRIMMED_UUID + " = ''");
            }
            validateStoredIdentities(connection);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        description VARCHAR(255) NOT NULL,
                        applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " + UUID_INDEX
                            + " ON scoreboard(uuid COLLATE NOCASE) WHERE "
                            + SQLITE_RESOLVED_UUID);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS " + RANKING_INDEX
                            + " ON scoreboard(score DESC, id ASC)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS seasons (
                        id VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(80) NOT NULL COLLATE NOCASE UNIQUE,
                        status VARCHAR(16) NOT NULL
                            CHECK (status IN ('PLANNED', 'ACTIVE', 'CLOSED', 'ARCHIVED')),
                        created_at INTEGER NOT NULL,
                        transitioned_at INTEGER NOT NULL,
                        activated_at INTEGER NULL,
                        closed_at INTEGER NULL,
                        archived_at INTEGER NULL
                    )
                    """);
            statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " + ACTIVE_SEASON_INDEX
                            + " ON seasons(status) WHERE status = 'ACTIVE'");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS season_scores (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        season_id VARCHAR(36) NOT NULL,
                        uuid VARCHAR(36) NOT NULL COLLATE NOCASE,
                        username VARCHAR(128) NOT NULL
                            CHECK (typeof(username) = 'text'
                                AND length(username) BETWEEN 3 AND 16
                                AND username NOT GLOB '*[^A-Za-z0-9_]*'),
                        score INTEGER NOT NULL DEFAULT 0 CHECK (score >= 0),
                        updated_at INTEGER NOT NULL,
                        UNIQUE (season_id, uuid),
                        FOREIGN KEY (season_id) REFERENCES seasons(id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS " + SEASON_RANKING_INDEX
                            + " ON season_scores(season_id, score DESC, id ASC)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS run_history (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id VARCHAR(36) NOT NULL UNIQUE,
                        player_uuid VARCHAR(36) NOT NULL COLLATE NOCASE,
                        username VARCHAR(128) NOT NULL,
                        arena_id VARCHAR(128) NOT NULL,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER NULL,
                        score INTEGER NULL CHECK (score IS NULL OR score >= 0),
                        end_reason VARCHAR(64) NULL,
                        release VARCHAR(128) NOT NULL,
                        season_id VARCHAR(36) NULL,
                        status VARCHAR(16) NOT NULL CHECK (status IN (
                            'STARTED', 'COMPLETED', 'ABORTED', 'UNKNOWN')),
                        FOREIGN KEY (season_id) REFERENCES seasons(id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS " + RUN_RECENT_INDEX
                            + " ON run_history(sequence DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS " + RUN_PLAYER_INDEX
                            + " ON run_history(player_uuid COLLATE NOCASE, sequence DESC)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reward_plans (
                        plan_id VARCHAR(36) PRIMARY KEY,
                        run_id VARCHAR(36) NOT NULL UNIQUE,
                        idempotency_key VARCHAR(128) NOT NULL UNIQUE,
                        status VARCHAR(16) NOT NULL CHECK (status IN (
                            'PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED',
                            'PARTIAL', 'UNKNOWN', 'ABANDONED')),
                        created_at INTEGER NOT NULL,
                        started_at INTEGER NULL,
                        completed_at INTEGER NULL,
                        FOREIGN KEY (run_id) REFERENCES run_history(run_id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS " + REWARD_PLAN_STATUS_INDEX
                            + " ON reward_plans(status, created_at DESC)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reward_steps (
                        plan_id VARCHAR(36) NOT NULL,
                        step_index INTEGER NOT NULL CHECK (step_index >= 0),
                        command_root VARCHAR(128) NOT NULL,
                        command_hash VARCHAR(64) NOT NULL,
                        status VARCHAR(16) NOT NULL CHECK (status IN (
                            'PENDING', 'DISPATCHING', 'SUCCEEDED', 'FAILED',
                            'UNKNOWN', 'SKIPPED')),
                        attempted_at INTEGER NULL,
                        completed_at INTEGER NULL,
                        PRIMARY KEY (plan_id, step_index),
                        FOREIGN KEY (plan_id) REFERENCES reward_plans(plan_id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS " + REWARD_STEP_STATUS_INDEX
                            + " ON reward_steps(status, plan_id)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reward_tombstones (
                        plan_id VARCHAR(36) PRIMARY KEY,
                        run_id VARCHAR(36) NOT NULL UNIQUE,
                        idempotency_key VARCHAR(128) NOT NULL UNIQUE,
                        terminal_status VARCHAR(16) NOT NULL CHECK (terminal_status IN (
                            'SUCCEEDED', 'FAILED', 'PARTIAL', 'ABANDONED')),
                        completed_at INTEGER NOT NULL,
                        pruned_at INTEGER NOT NULL
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_preferences (
                        uuid VARCHAR(36) PRIMARY KEY COLLATE NOCASE,
                        particle_mode VARCHAR(16) NOT NULL
                            CHECK (particle_mode IN ('FULL', 'REDUCED', 'OFF')),
                        sounds_enabled INTEGER NOT NULL
                            CHECK (sounds_enabled IN (0, 1)),
                        titles_enabled INTEGER NOT NULL
                            CHECK (titles_enabled IN (0, 1)),
                        updated_at INTEGER NOT NULL
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS category_scores (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        category VARCHAR(16) NOT NULL
                            CHECK (category IN ('COMBO', 'FLAWLESS')),
                        uuid VARCHAR(36) NOT NULL COLLATE NOCASE,
                        username VARCHAR(128) NOT NULL
                            CHECK (typeof(username) = 'text'
                                AND length(username) BETWEEN 3 AND 16
                                AND username NOT GLOB '*[^A-Za-z0-9_]*'),
                        score INTEGER NOT NULL CHECK (score >= 1),
                        updated_at INTEGER NOT NULL,
                        UNIQUE (category, uuid)
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS " + CATEGORY_RANKING_INDEX
                            + " ON category_scores(category, score DESC, id ASC)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS run_category_scores (
                        run_id VARCHAR(36) NOT NULL,
                        category VARCHAR(16) NOT NULL
                            CHECK (category IN ('COMBO', 'FLAWLESS')),
                        score INTEGER NOT NULL CHECK (score >= 1),
                        PRIMARY KEY (run_id, category),
                        FOREIGN KEY (run_id) REFERENCES run_history(run_id) ON DELETE CASCADE
                    )
                    """);

            validateRequiredSchema(connection);

            try (PreparedStatement migration = connection.prepareStatement(
                    "INSERT OR IGNORE INTO schema_migrations (version, description) VALUES (?, ?)")) {
                migration.setInt(1, 1);
                migration.setString(2, "Modern UUID score repository");
                migration.executeUpdate();
                migration.setInt(1, 2);
                migration.setString(2, "Seasons, bounded run history, and reward execution ledger");
                migration.executeUpdate();
                migration.setInt(1, SCHEMA_VERSION);
                migration.setString(
                        2,
                        "Accessibility preferences and separate combo/flawless categories");
                migration.executeUpdate();
            }
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException | ScoreRepositoryException exception) {
            rollback(connection, exception);
            throw exception;
        }
    }

    private Path createVerifiedSQLiteBackup(Path databasePath)
            throws SQLException, IOException, ScoreRepositoryException {
        Path backupPath = nextBackupPath(databasePath);
        try (Connection source = openConnection(); Statement statement = source.createStatement()) {
            String escapedPath = backupPath.toString().replace("'", "''");
            statement.execute("VACUUM INTO '" + escapedPath + "'");
        }

        if (!Files.isRegularFile(backupPath) || Files.size(backupPath) == 0L) {
            throw new ScoreRepositoryException("SQLite did not create a usable backup at " + backupPath);
        }

        verifySQLiteBackup(backupPath);
        return backupPath;
    }

    private static void verifySQLiteBackup(Path backupPath)
            throws SQLException, IOException, ScoreRepositoryException {
        if (Files.isSymbolicLink(backupPath)
                || !Files.isRegularFile(backupPath, LinkOption.NOFOLLOW_LINKS)
                || Files.size(backupPath) == 0L) {
            throw new ScoreRepositoryException("SQLite migration backup is not a safe regular file");
        }
        String backupUrl = "jdbc:sqlite:" + backupPath;
        try (Connection backup = DriverManager.getConnection(backupUrl);
                Statement statement = backup.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            int rowCount = 0;
            boolean ok = false;
            while (result.next()) {
                rowCount++;
                ok = "ok".equalsIgnoreCase(result.getString(1));
            }
            if (rowCount != 1 || !ok) {
                throw new ScoreRepositoryException(
                        "SQLite backup failed PRAGMA quick_check: " + backupPath);
            }
        }
    }

    private int enforceMigrationBackupRetention(Path databasePath)
            throws IOException, SQLException, ScoreRepositoryException {
        Path parent = databasePath.getParent();
        if (parent == null
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SQLite backup directory is unavailable");
        }
        String prefix = databasePath.getFileName() + ".pre-migration-v";
        Pattern automaticBackup = automaticMigrationBackupPattern(prefix);
        List<AutomaticMigrationBackup> backups = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                Matcher matcher = automaticBackup.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                            "Automatic SQLite backup entry is not a safe regular file");
                }
                backups.add(parseAutomaticMigrationBackup(entry, matcher));
            }
        }
        backups.sort(Comparator.comparingLong(AutomaticMigrationBackup::createdAt)
                .reversed()
                .thenComparing(
                        Comparator.comparingInt(AutomaticMigrationBackup::schemaVersion)
                                .reversed())
                .thenComparing(
                        Comparator.comparingInt(AutomaticMigrationBackup::suffix)
                                .reversed()));
        for (AutomaticMigrationBackup backup : backups) {
            verifySQLiteBackup(backup.path());
        }
        int pruned = 0;
        while (backups.size() > settings.migrationBackupRetention()) {
            AutomaticMigrationBackup oldest = backups.removeLast();
            Files.delete(oldest.path());
            pruned++;
        }
        if (pruned > 0) {
            forceDirectory(parent);
            logger.info("Pruned " + pruned
                    + " verified automatic SQLite migration backup(s); retained "
                    + settings.migrationBackupRetention());
        }
        return pruned;
    }

    private static Path nextBackupPath(Path databasePath) {
        String baseName = databasePath.getFileName().toString()
                + ".pre-migration-v" + SCHEMA_VERSION + '-' + Instant.now().toEpochMilli();
        Path parent = databasePath.getParent();
        Path backup = parent.resolve(baseName + ".sqlite");
        int suffix = 1;
        while (Files.exists(backup)) {
            backup = parent.resolve(baseName + '-' + suffix + ".sqlite");
            suffix++;
        }
        return backup;
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private CompletedRunResult recordCompletedRun(Connection connection, CompletedRun run)
            throws SQLException, ScoreRepositoryException {
        RunRecord started = startRun(connection, run.start(), run.seasonId());
        requireMatchingCompletedRun(started, run);
        return completeRun(connection, run.completion());
    }

    private RunRecord startRun(
            Connection connection,
            RunStart run,
            Optional<UUID> requestedSeasonId) throws SQLException, ScoreRepositoryException {
        RunRecord existing = findRun(connection, run.id());
        if (existing != null) {
            requireMatchingRunStart(existing, run, requestedSeasonId);
            return existing;
        }
        Optional<Season> season = resolveRunSeason(connection, requestedSeasonId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_history (
                    run_id, player_uuid, username, arena_id, started_at,
                    ended_at, score, end_reason, release, season_id, status
                ) VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, 'STARTED')
                """)) {
            statement.setString(1, run.id().toString());
            statement.setString(2, run.playerId().toString());
            statement.setString(3, run.username());
            statement.setString(4, run.arenaId());
            statement.setLong(5, run.startedAt().toEpochMilli());
            statement.setString(6, run.release());
            if (season.isPresent()) {
                statement.setString(7, season.orElseThrow().id().toString());
            } else {
                statement.setNull(7, java.sql.Types.VARCHAR);
            }
            statement.executeUpdate();
        }
        return Objects.requireNonNull(findRun(connection, run.id()));
    }

    private CompletedRunResult completeRun(Connection connection, RunCompletion completion)
            throws SQLException, ScoreRepositoryException {
        RunRecord run = findRun(connection, completion.runId());
        if (run == null) {
            throw new ScoreRepositoryException("Unknown run " + completion.runId());
        }
        if (run.status() == RunStatus.COMPLETED) {
            requireMatchingRunCompletion(run, completion);
            return duplicateCompletedRunResult(
                    connection, run, completion.categoryScores());
        }
        if (run.status() != RunStatus.STARTED) {
            throw new ScoreRepositoryException(
                    "Run " + run.id() + " is " + run.status() + " and cannot be completed");
        }
        if (completion.endedAt().isBefore(run.startedAt())) {
            throw new ScoreRepositoryException("Run endedAt must not be before startedAt");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE run_history
                SET ended_at = ?, score = ?, end_reason = ?, status = 'COMPLETED'
                WHERE run_id = ? AND status = 'STARTED'
                """)) {
            statement.setLong(1, completion.endedAt().toEpochMilli());
            statement.setInt(2, completion.score());
            statement.setString(3, completion.endReason());
            statement.setString(4, completion.runId().toString());
            if (statement.executeUpdate() != 1) {
                throw new ScoreRepositoryException(
                        "Run state changed while completing " + completion.runId());
            }
        }
        Optional<ScoreUpdateResult> allTime = Optional.empty();
        Optional<ScoreUpdateResult> seasonScore = Optional.empty();
        EnumMap<ScoreCategory, ScoreUpdateResult> categoryScores =
                new EnumMap<>(ScoreCategory.class);
        if (completion.score() > 0) {
            allTime = Optional.of(upsertAllTimeScore(
                    connection, run.playerId(), run.username(), completion.score()));
        }
        if (completion.score() > 0 && run.seasonId().isPresent()) {
            seasonScore = Optional.of(upsertSeasonScore(
                    connection,
                    run.seasonId().orElseThrow(),
                    run.playerId(),
                    run.username(),
                    completion.score(),
                    completion.endedAt()));
        }
        for (RunCategoryScore categoryScore : completion.categoryScores()) {
            insertRunCategoryScore(connection, completion.runId(), categoryScore);
            categoryScores.put(
                    categoryScore.category(),
                    upsertCategoryScore(
                            connection,
                            categoryScore.category(),
                            run.playerId(),
                            run.username(),
                            categoryScore.score(),
                            completion.endedAt()));
        }
        pruneRunHistory(connection, runHistoryRetentionLimit, run.id());
        RunRecord stored = Objects.requireNonNull(
                findRun(connection, run.id()), "Completed run was not found");
        return new CompletedRunResult(
                stored, allTime, seasonScore, categoryScores, true);
    }

    private CompletedRunWithRewardPlanResult completeRunWithRewardPlan(
            Connection connection,
            RunCompletion completion,
            Optional<RewardPlanRequest> rewardPlan)
            throws SQLException, ScoreRepositoryException {
        CompletedRunResult completed = completeRun(connection, completion);
        Optional<RewardPlanBeginResult> persistedPlan;
        if (rewardPlan.isPresent()) {
            RewardPlanRequest request = rewardPlan.orElseThrow();
            if (completed.created()) {
                RewardPlanBeginResult begun = beginRewardPlan(connection, request);
                if (!begun.created()) {
                    throw new ScoreRepositoryException(
                            "A newly completed run already had a reward plan");
                }
                persistedPlan = Optional.of(begun);
            } else {
                persistedPlan = Optional.of(requireExistingAtomicRewardPlan(
                        connection, request));
            }
        } else {
            RewardPlanRecord existing = findRewardPlanByRunId(
                    connection, completion.runId());
            if (existing != null) {
                throw new ScoreRepositoryException(
                        "Run completion reward-plan shape conflicts with its committed retry");
            }
            persistedPlan = Optional.empty();
        }
        return new CompletedRunWithRewardPlanResult(completed, persistedPlan);
    }

    private RunRecord markRunInterrupted(
            Connection connection,
            UUID runId,
            RunStatus status,
            Instant endedAt,
            String reason) throws SQLException, ScoreRepositoryException {
        RunRecord run = findRun(connection, runId);
        if (run == null) {
            throw new ScoreRepositoryException("Unknown run " + runId);
        }
        if (run.status() == status
                && run.endedAt().filter(stored -> sameStoredInstant(stored, endedAt)).isPresent()
                && run.endReason().equals(Optional.of(reason))) {
            return run;
        }
        boolean resolvableUnknown = run.status() == RunStatus.UNKNOWN
                && status == RunStatus.ABORTED;
        if (run.status() != RunStatus.STARTED && !resolvableUnknown) {
            throw new ScoreRepositoryException(
                    "Run " + runId + " is " + run.status() + " and cannot become " + status);
        }
        if (endedAt.isBefore(run.startedAt())) {
            throw new ScoreRepositoryException("Run endedAt must not be before startedAt");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE run_history
                SET ended_at = ?, score = NULL, end_reason = ?, status = ?
                WHERE run_id = ?
                """)) {
            statement.setLong(1, endedAt.toEpochMilli());
            statement.setString(2, reason);
            statement.setString(3, status.name());
            statement.setString(4, runId.toString());
            statement.executeUpdate();
        }
        pruneRunHistory(connection, runHistoryRetentionLimit, runId);
        return Objects.requireNonNull(findRun(connection, runId));
    }

    private static void requireMatchingCompletedRun(RunRecord existing, CompletedRun requested)
            throws ScoreRepositoryException {
        boolean seasonMatches = requested.seasonId().isEmpty()
                || requested.seasonId().equals(existing.seasonId());
        if (!existing.id().equals(requested.id())
                || !existing.playerId().equals(requested.playerId())
                || !existing.username().equals(requested.username())
                || !existing.arenaId().equals(requested.arenaId())
                || !sameStoredInstant(existing.startedAt(), requested.startedAt())
                || !existing.release().equals(requested.release())
                || !seasonMatches) {
            throw new ScoreRepositoryException(
                    "Run idempotency conflict for " + requested.id());
        }
    }

    private static void requireMatchingRunStart(
            RunRecord existing,
            RunStart requested,
            Optional<UUID> requestedSeasonId) throws ScoreRepositoryException {
        boolean seasonMatches = requestedSeasonId.isEmpty()
                || requestedSeasonId.equals(existing.seasonId());
        if (!existing.id().equals(requested.id())
                || !existing.playerId().equals(requested.playerId())
                || !existing.username().equals(requested.username())
                || !existing.arenaId().equals(requested.arenaId())
                || !sameStoredInstant(existing.startedAt(), requested.startedAt())
                || !existing.release().equals(requested.release())
                || !seasonMatches) {
            throw new ScoreRepositoryException(
                    "Run idempotency conflict for " + requested.id());
        }
    }

    private static void requireMatchingRunCompletion(
            RunRecord existing,
            RunCompletion requested) throws ScoreRepositoryException {
        if (existing.endedAt()
                        .filter(stored -> sameStoredInstant(stored, requested.endedAt()))
                        .isEmpty()
                || !existing.score().equals(Optional.of(requested.score()))
                || !existing.endReason().equals(Optional.of(requested.endReason()))) {
            throw new ScoreRepositoryException(
                    "Run completion idempotency conflict for " + requested.runId());
        }
    }

    private CompletedRunResult duplicateCompletedRunResult(
            Connection connection,
            RunRecord run,
            List<RunCategoryScore> requestedCategoryScores)
            throws SQLException, ScoreRepositoryException {
        Map<ScoreCategory, Integer> storedCategoryScores =
                loadRunCategoryScores(connection, run.id());
        Map<ScoreCategory, Integer> requested = new EnumMap<>(ScoreCategory.class);
        for (RunCategoryScore categoryScore : requestedCategoryScores) {
            requested.put(categoryScore.category(), categoryScore.score());
        }
        if (!storedCategoryScores.equals(requested)) {
            throw new ScoreRepositoryException(
                    "Run completion category shape conflicts with its committed retry");
        }
        int score = run.score().orElseThrow();
        if (score == 0) {
            return new CompletedRunResult(
                    run, Optional.empty(), Optional.empty(), Map.of(), false);
        }
        ExistingScore allTimeExisting = findExistingScore(connection, run.playerId());
        if (allTimeExisting == null || allTimeExisting.score() < score) {
            throw new ScoreRepositoryException(
                    "Run " + run.id() + " exists without its expected score projection");
        }
        Optional<ScoreUpdateResult> allTime = Optional.of(new ScoreUpdateResult(
                run.playerId(),
                run.username(),
                score,
                allTimeExisting.score(),
                allTimeExisting.score(),
                false,
                false));
        Optional<ScoreUpdateResult> seasonScore = Optional.empty();
        if (run.seasonId().isPresent()) {
            ExistingScore existing = findExistingSeasonScore(
                    connection, run.seasonId().orElseThrow(), run.playerId());
            if (existing == null || existing.score() < score) {
                throw new ScoreRepositoryException(
                        "Run " + run.id() + " exists without its expected season score projection");
            }
            seasonScore = Optional.of(new ScoreUpdateResult(
                    run.playerId(),
                    run.username(),
                    score,
                    existing.score(),
                    existing.score(),
                    false,
                    false));
        }
        EnumMap<ScoreCategory, ScoreUpdateResult> categoryResults =
                new EnumMap<>(ScoreCategory.class);
        for (Map.Entry<ScoreCategory, Integer> entry : storedCategoryScores.entrySet()) {
            ExistingScore existing =
                    findExistingCategoryScore(connection, entry.getKey(), run.playerId());
            if (existing == null || existing.score() < entry.getValue()) {
                throw new ScoreRepositoryException(
                        "Run " + run.id()
                                + " exists without its expected category score projection");
            }
            categoryResults.put(
                    entry.getKey(),
                    new ScoreUpdateResult(
                            run.playerId(),
                            run.username(),
                            entry.getValue(),
                            existing.score(),
                            existing.score(),
                            false,
                            false));
        }
        return new CompletedRunResult(
                run, allTime, seasonScore, categoryResults, false);
    }

    private Optional<Season> resolveRunSeason(
            Connection connection,
            Optional<UUID> requestedSeasonId) throws SQLException, ScoreRepositoryException {
        Season season;
        if (requestedSeasonId.isPresent()) {
            season = findSeason(connection, requestedSeasonId.orElseThrow());
            if (season == null) {
                throw new ScoreRepositoryException(
                        "Unknown season " + requestedSeasonId.orElseThrow());
            }
        } else {
            season = findActiveSeason(connection);
            if (season == null) {
                return Optional.empty();
            }
        }
        if (season.status() != SeasonStatus.ACTIVE) {
            throw new ScoreRepositoryException(
                    "Completed runs can only be assigned to an active season: " + season.id());
        }
        return Optional.of(season);
    }

    private ScoreUpdateResult upsertAllTimeScore(
            Connection connection,
            UUID uuid,
            String username,
            int score) throws SQLException {
        ExistingScore existing = findExistingScore(connection, uuid);
        if (existing == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO scoreboard (score, uuid, username, updated_at) "
                            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
                statement.setInt(1, score);
                statement.setString(2, uuid.toString());
                statement.setString(3, username);
                statement.executeUpdate();
            }
            return new ScoreUpdateResult(uuid, username, score, 0, score, score > 0, true);
        }

        int bestScore = Math.max(existing.score(), score);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE scoreboard SET score = ?, username = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?")) {
            statement.setInt(1, bestScore);
            statement.setString(2, username);
            statement.setLong(3, existing.id());
            statement.executeUpdate();
        }
        return new ScoreUpdateResult(
                uuid,
                username,
                score,
                existing.score(),
                bestScore,
                score > existing.score(),
                false);
    }

    private ScoreUpdateResult upsertSeasonScore(
            Connection connection,
            UUID seasonId,
            UUID uuid,
            String username,
            int score,
            Instant updatedAt) throws SQLException {
        ExistingScore existing = findExistingSeasonScore(connection, seasonId, uuid);
        if (existing == null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO season_scores (
                        season_id, uuid, username, score, updated_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, seasonId.toString());
                statement.setString(2, uuid.toString());
                statement.setString(3, username);
                statement.setInt(4, score);
                statement.setLong(5, updatedAt.toEpochMilli());
                statement.executeUpdate();
            }
            return new ScoreUpdateResult(uuid, username, score, 0, score, score > 0, true);
        }

        int bestScore = Math.max(existing.score(), score);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE season_scores
                SET score = ?, username = ?, updated_at = ?
                WHERE id = ?
                """)) {
            statement.setInt(1, bestScore);
            statement.setString(2, username);
            statement.setLong(3, updatedAt.toEpochMilli());
            statement.setLong(4, existing.id());
            statement.executeUpdate();
        }
        return new ScoreUpdateResult(
                uuid,
                username,
                score,
                existing.score(),
                bestScore,
                score > existing.score(),
                false);
    }

    private static void insertRunCategoryScore(
            Connection connection,
            UUID runId,
            RunCategoryScore categoryScore) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_category_scores (run_id, category, score)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, runId.toString());
            statement.setString(2, categoryScore.category().name());
            statement.setInt(3, categoryScore.score());
            statement.executeUpdate();
        }
    }

    private ScoreUpdateResult upsertCategoryScore(
            Connection connection,
            ScoreCategory category,
            UUID uuid,
            String username,
            int score,
            Instant updatedAt) throws SQLException {
        ExistingScore existing = findExistingCategoryScore(connection, category, uuid);
        if (existing == null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO category_scores (
                        category, uuid, username, score, updated_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, category.name());
                statement.setString(2, uuid.toString());
                statement.setString(3, username);
                statement.setInt(4, score);
                statement.setLong(5, updatedAt.toEpochMilli());
                statement.executeUpdate();
            }
            return new ScoreUpdateResult(uuid, username, score, 0, score, true, true);
        }

        int bestScore = Math.max(existing.score(), score);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE category_scores
                SET score = ?, username = ?, updated_at = ?
                WHERE id = ?
                """)) {
            statement.setInt(1, bestScore);
            statement.setString(2, username);
            statement.setLong(3, updatedAt.toEpochMilli());
            statement.setLong(4, existing.id());
            statement.executeUpdate();
        }
        return new ScoreUpdateResult(
                uuid,
                username,
                score,
                existing.score(),
                bestScore,
                score > existing.score(),
                false);
    }

    private static PlayerPreferences upsertPlayerPreferences(
            Connection connection,
            PlayerPreferences preferences) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_preferences (
                    uuid, particle_mode, sounds_enabled, titles_enabled, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    particle_mode = excluded.particle_mode,
                    sounds_enabled = excluded.sounds_enabled,
                    titles_enabled = excluded.titles_enabled,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, preferences.playerId().toString());
            statement.setString(2, preferences.particles().name());
            statement.setInt(3, preferences.soundsEnabled() ? 1 : 0);
            statement.setInt(4, preferences.titlesEnabled() ? 1 : 0);
            statement.setLong(5, preferences.updatedAt().toEpochMilli());
            statement.executeUpdate();
        }
        return preferences;
    }

    private CompletableFuture<PlayerPreferences> updatePreferenceField(
            UUID playerId,
            String operation,
            UnaryOperator<PlayerPreferences> update) {
        return submitMutation(() -> mutateWithRetry(
                operation,
                playerId,
                connection -> upsertPlayerPreferences(
                        connection,
                        update.apply(findPlayerPreferences(connection, playerId)))));
    }

    private static PlayerPreferences findPlayerPreferences(
            Connection connection,
            UUID playerId) throws SQLException, ScoreRepositoryException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT particle_mode, sounds_enabled, titles_enabled, updated_at
                FROM player_preferences
                WHERE uuid = ? COLLATE NOCASE
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return PlayerPreferences.defaults(playerId);
                }
                PlayerPreferences preferences;
                try {
                    preferences = new PlayerPreferences(
                            playerId,
                            ParticlePreference.parse(result.getString("particle_mode")),
                            result.getInt("sounds_enabled") == 1,
                            result.getInt("titles_enabled") == 1,
                            Instant.ofEpochMilli(result.getLong("updated_at")));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw new ScoreRepositoryException(
                            "player_preferences contains an invalid preference row",
                            exception);
                }
                if (result.next()) {
                    throw new ScoreRepositoryException(
                            "player_preferences contains a duplicate UUID");
                }
                return preferences;
            }
        }
    }

    private Season createSeason(Connection connection, Season requested)
            throws SQLException, ScoreRepositoryException {
        Season existing = findSeason(connection, requested.id());
        if (existing != null) {
            if (existing.equals(requested)) {
                return existing;
            }
            throw new ScoreRepositoryException(
                    "Season idempotency conflict for " + requested.id());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO seasons (
                    id, name, status, created_at, transitioned_at,
                    activated_at, closed_at, archived_at
                ) VALUES (?, ?, 'PLANNED', ?, ?, NULL, NULL, NULL)
                """)) {
            statement.setString(1, requested.id().toString());
            statement.setString(2, requested.name());
            statement.setLong(3, requested.createdAt().toEpochMilli());
            statement.setLong(4, requested.transitionedAt().toEpochMilli());
            statement.executeUpdate();
        }
        return Objects.requireNonNull(findSeason(connection, requested.id()));
    }

    private Season activateSeason(Connection connection, UUID id, Instant activatedAt)
            throws SQLException, ScoreRepositoryException {
        Season season = requireSeason(connection, id);
        requireMonotonicSeasonTransition(season, activatedAt, "activatedAt");
        if (season.status() == SeasonStatus.ACTIVE) {
            return season;
        }
        if (season.status() != SeasonStatus.PLANNED) {
            throw new ScoreRepositoryException(
                    "Only a planned season can be activated: " + id + " is " + season.status());
        }
        Season active = findActiveSeason(connection);
        if (active != null && !active.id().equals(id)) {
            throw new ScoreRepositoryException(
                    "Close active season " + active.id() + " before activating " + id);
        }
        Season updated = new Season(
                season.id(),
                season.name(),
                SeasonStatus.ACTIVE,
                season.createdAt(),
                activatedAt,
                Optional.of(activatedAt),
                Optional.empty(),
                Optional.empty());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE seasons
                SET status = 'ACTIVE', transitioned_at = ?, activated_at = ?,
                    closed_at = NULL, archived_at = NULL
                WHERE id = ?
                """)) {
            statement.setLong(1, updated.transitionedAt().toEpochMilli());
            statement.setLong(2, updated.activatedAt().orElseThrow().toEpochMilli());
            statement.setString(3, id.toString());
            statement.executeUpdate();
        }
        return updated;
    }

    private Season closeSeason(Connection connection, UUID id, Instant closedAt)
            throws SQLException, ScoreRepositoryException {
        Season season = requireSeason(connection, id);
        requireMonotonicSeasonTransition(season, closedAt, "closedAt");
        if (season.status() == SeasonStatus.CLOSED) {
            return season;
        }
        if (season.status() == SeasonStatus.ARCHIVED) {
            throw new ScoreRepositoryException("An archived season cannot be closed again: " + id);
        }
        Season updated = new Season(
                season.id(),
                season.name(),
                SeasonStatus.CLOSED,
                season.createdAt(),
                closedAt,
                season.activatedAt(),
                Optional.of(closedAt),
                Optional.empty());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE seasons
                SET status = 'CLOSED', transitioned_at = ?, closed_at = ?, archived_at = NULL
                WHERE id = ?
                """)) {
            statement.setLong(1, updated.transitionedAt().toEpochMilli());
            statement.setLong(2, updated.closedAt().orElseThrow().toEpochMilli());
            statement.setString(3, id.toString());
            statement.executeUpdate();
        }
        return updated;
    }

    private Season reopenSeason(Connection connection, UUID id, Instant reopenedAt)
            throws SQLException, ScoreRepositoryException {
        Season season = requireSeason(connection, id);
        requireMonotonicSeasonTransition(season, reopenedAt, "reopenedAt");
        if (season.status() == SeasonStatus.PLANNED) {
            return season;
        }
        if (season.status() != SeasonStatus.CLOSED) {
            throw new ScoreRepositoryException(
                    "Only a closed season can be reopened: " + id + " is " + season.status());
        }
        Season updated = new Season(
                season.id(),
                season.name(),
                SeasonStatus.PLANNED,
                season.createdAt(),
                reopenedAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE seasons
                SET status = 'PLANNED', transitioned_at = ?,
                    activated_at = NULL, closed_at = NULL, archived_at = NULL
                WHERE id = ?
                """)) {
            statement.setLong(1, updated.transitionedAt().toEpochMilli());
            statement.setString(2, id.toString());
            statement.executeUpdate();
        }
        return updated;
    }

    private Season archiveSeason(Connection connection, UUID id, Instant archivedAt)
            throws SQLException, ScoreRepositoryException {
        Season season = requireSeason(connection, id);
        requireMonotonicSeasonTransition(season, archivedAt, "archivedAt");
        if (season.status() == SeasonStatus.ARCHIVED) {
            return season;
        }
        if (season.status() != SeasonStatus.CLOSED) {
            throw new ScoreRepositoryException(
                    "Only a closed season can be archived: " + id + " is " + season.status());
        }
        Season updated = new Season(
                season.id(),
                season.name(),
                SeasonStatus.ARCHIVED,
                season.createdAt(),
                archivedAt,
                season.activatedAt(),
                season.closedAt(),
                Optional.of(archivedAt));
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE seasons
                SET status = 'ARCHIVED', transitioned_at = ?, archived_at = ?
                WHERE id = ?
                """)) {
            statement.setLong(1, updated.transitionedAt().toEpochMilli());
            statement.setLong(2, updated.archivedAt().orElseThrow().toEpochMilli());
            statement.setString(3, id.toString());
            statement.executeUpdate();
        }
        return updated;
    }

    private static void requireMonotonicSeasonTransition(
            Season season,
            Instant transitionAt,
            String fieldName) throws ScoreRepositoryException {
        if (transitionAt.isBefore(season.transitionedAt())) {
            throw new ScoreRepositoryException(
                    fieldName + " must not precede the season's current transitionedAt");
        }
    }

    private Season requireSeason(Connection connection, UUID id)
            throws SQLException, ScoreRepositoryException {
        Season season = findSeason(connection, id);
        if (season == null) {
            throw new ScoreRepositoryException("Unknown season " + id);
        }
        return season;
    }

    private Season findSeason(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, status, created_at, transitioned_at,
                       activated_at, closed_at, archived_at
                FROM seasons WHERE id = ?
                """)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readSeason(result) : null;
            }
        }
    }

    private Season findActiveSeason(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT id, name, status, created_at, transitioned_at,
                               activated_at, closed_at, archived_at
                        FROM seasons WHERE status = 'ACTIVE'
                        """)) {
            if (!result.next()) {
                return null;
            }
            Season active = readSeason(result);
            if (result.next()) {
                throw new SQLException("Multiple active seasons bypassed the unique index");
            }
            return active;
        }
    }

    private static Season readSeason(ResultSet result) throws SQLException {
        return new Season(
                UUID.fromString(result.getString("id")),
                result.getString("name"),
                SeasonStatus.valueOf(result.getString("status")),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("transitioned_at")),
                optionalEpochMillis(result, "activated_at"),
                optionalEpochMillis(result, "closed_at"),
                optionalEpochMillis(result, "archived_at"));
    }

    private RewardPlanBeginResult beginRewardPlan(
            Connection connection,
            RewardPlanRequest request) throws SQLException, ScoreRepositoryException {
        requireNoRewardTombstone(connection, request);
        RewardPlanRecord existing = findRewardPlanByAnyKey(connection, request);
        if (existing != null) {
            return existingRewardPlanResult(connection, existing, request);
        }
        requireCompletedRewardRun(connection, request.runId());

        RewardPlanStatus initialStatus = request.steps().isEmpty()
                ? RewardPlanStatus.SUCCEEDED
                : RewardPlanStatus.PENDING;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_plans (
                    plan_id, run_id, idempotency_key, status,
                    created_at, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, NULL, ?)
                """)) {
            statement.setString(1, request.planId().toString());
            statement.setString(2, request.runId().toString());
            statement.setString(3, request.idempotencyKey());
            statement.setString(4, initialStatus.name());
            statement.setLong(5, request.createdAt().toEpochMilli());
            if (initialStatus == RewardPlanStatus.SUCCEEDED) {
                statement.setLong(6, request.createdAt().toEpochMilli());
            } else {
                statement.setNull(6, java.sql.Types.BIGINT);
            }
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_steps (
                    plan_id, step_index, command_root, command_hash,
                    status, attempted_at, completed_at
                ) VALUES (?, ?, ?, ?, 'PENDING', NULL, NULL)
                """)) {
            for (int index = 0; index < request.steps().size(); index++) {
                RewardStepSpec step = request.steps().get(index);
                statement.setString(1, request.planId().toString());
                statement.setInt(2, index);
                statement.setString(3, step.commandRoot());
                statement.setString(4, step.commandHash());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        RewardPlanRecord stored = Objects.requireNonNull(
                findRewardPlan(connection, request.planId()),
                "Inserted reward plan was not found");
        return new RewardPlanBeginResult(stored, true, !stored.steps().isEmpty());
    }

    private RewardPlanBeginResult requireExistingAtomicRewardPlan(
            Connection connection,
            RewardPlanRequest request) throws SQLException, ScoreRepositoryException {
        requireNoRewardTombstone(connection, request);
        RewardPlanRecord existing = findRewardPlanByAnyKey(connection, request);
        if (existing == null) {
            throw new ScoreRepositoryException(
                    "A completed run cannot be retroactively attached to a reward plan");
        }
        requireMatchingRewardPlan(existing, request);
        requireCompletedRewardRun(connection, request.runId());
        return new RewardPlanBeginResult(existing, false, false);
    }

    private RewardPlanBeginResult existingRewardPlanResult(
            Connection connection,
            RewardPlanRecord existing,
            RewardPlanRequest request) throws SQLException, ScoreRepositoryException {
        requireMatchingRewardPlan(existing, request);
        requireCompletedRewardRun(connection, request.runId());
        boolean resumable = existing.status() == RewardPlanStatus.PENDING
                && !existing.steps().isEmpty()
                && existing.steps().stream()
                        .allMatch(step -> step.status() == RewardStepStatus.PENDING);
        return new RewardPlanBeginResult(existing, false, resumable);
    }

    private RunRecord requireCompletedRewardRun(Connection connection, UUID runId)
            throws SQLException, ScoreRepositoryException {
        RunRecord run = findRun(connection, runId);
        if (run == null) {
            throw new ScoreRepositoryException(
                    "Reward plan references unknown run " + runId);
        }
        if (run.status() != RunStatus.COMPLETED) {
            throw new ScoreRepositoryException(
                    "Reward plan requires a completed run; " + runId + " is " + run.status());
        }
        return run;
    }

    private RewardStepDispatchResult markRewardStepDispatching(
            Connection connection,
            UUID planId,
            int stepIndex,
            Instant attemptedAt) throws SQLException, ScoreRepositoryException {
        RewardPlanRecord plan = requireRewardPlan(connection, planId);
        RewardStepRecord step = findStep(plan, stepIndex);
        if (plan.status() != RewardPlanStatus.PENDING
                && plan.status() != RewardPlanStatus.IN_PROGRESS) {
            return new RewardStepDispatchResult(plan, false);
        }
        if (step.status() != RewardStepStatus.PENDING
                || !previousStepsSucceeded(plan, stepIndex)) {
            return new RewardStepDispatchResult(plan, false);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_steps
                SET status = 'DISPATCHING', attempted_at = ?, completed_at = NULL
                WHERE plan_id = ? AND step_index = ? AND status = 'PENDING'
                """)) {
            statement.setLong(1, attemptedAt.toEpochMilli());
            statement.setString(2, planId.toString());
            statement.setInt(3, stepIndex);
            if (statement.executeUpdate() != 1) {
                return new RewardStepDispatchResult(
                        requireRewardPlan(connection, planId), false);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_plans
                SET status = 'IN_PROGRESS', started_at = COALESCE(started_at, ?),
                    completed_at = NULL
                WHERE plan_id = ?
                """)) {
            statement.setLong(1, attemptedAt.toEpochMilli());
            statement.setString(2, planId.toString());
            statement.executeUpdate();
        }
        return new RewardStepDispatchResult(
                requireRewardPlan(connection, planId), true);
    }

    private RewardPlanRecord completeRewardStep(
            Connection connection,
            UUID planId,
            int stepIndex,
            RewardStepStatus outcome,
            Instant completedAt) throws SQLException, ScoreRepositoryException {
        RewardPlanRecord plan = requireRewardPlan(connection, planId);
        RewardStepRecord step = findStep(plan, stepIndex);
        if (step.status() == outcome) {
            return plan;
        }
        if (step.status() != RewardStepStatus.DISPATCHING) {
            throw new ScoreRepositoryException(
                    "Reward step " + stepIndex + " for plan " + planId
                            + " is " + step.status() + ", not DISPATCHING");
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_steps
                SET status = ?, completed_at = ?
                WHERE plan_id = ? AND step_index = ? AND status = 'DISPATCHING'
                """)) {
            statement.setString(1, outcome.name());
            statement.setLong(2, completedAt.toEpochMilli());
            statement.setString(3, planId.toString());
            statement.setInt(4, stepIndex);
            if (statement.executeUpdate() != 1) {
                throw new ScoreRepositoryException(
                        "Reward step state changed while completing plan " + planId);
            }
        }
        if (outcome != RewardStepStatus.SUCCEEDED) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reward_steps
                    SET status = 'SKIPPED', completed_at = ?
                    WHERE plan_id = ? AND status = 'PENDING'
                    """)) {
                statement.setLong(1, completedAt.toEpochMilli());
                statement.setString(2, planId.toString());
                statement.executeUpdate();
            }
        }
        return refreshRewardPlanStatus(connection, planId, completedAt);
    }

    private RewardPlanRecord resolveUnknownRewardStep(
            Connection connection,
            UUID planId,
            int stepIndex,
            RewardStepStatus resolution,
            Instant resolvedAt) throws SQLException, ScoreRepositoryException {
        RewardPlanRecord plan = requireRewardPlan(connection, planId);
        RewardStepRecord step = findStep(plan, stepIndex);
        if (plan.status() != RewardPlanStatus.UNKNOWN
                || step.status() != RewardStepStatus.UNKNOWN) {
            throw new ScoreRepositoryException(
                    "Reward step " + stepIndex + " for plan " + planId
                            + " is not an unresolved UNKNOWN step");
        }
        if (step.attemptedAt().isPresent()
                && resolvedAt.isBefore(step.attemptedAt().orElseThrow())) {
            throw new ScoreRepositoryException(
                    "Reward resolution time must not precede the dispatch attempt");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_steps
                SET status = ?, completed_at = ?
                WHERE plan_id = ? AND step_index = ? AND status = 'UNKNOWN'
                """)) {
            statement.setString(1, resolution.name());
            statement.setLong(2, resolvedAt.toEpochMilli());
            statement.setString(3, planId.toString());
            statement.setInt(4, stepIndex);
            if (statement.executeUpdate() != 1) {
                throw new ScoreRepositoryException(
                        "Reward step state changed while resolving plan " + planId);
            }
        }
        return refreshRewardPlanStatus(connection, planId, resolvedAt);
    }

    private RewardPlanRecord refreshRewardPlanStatus(
            Connection connection,
            UUID planId,
            Instant statusTime) throws SQLException, ScoreRepositoryException {
        RewardPlanRecord current = requireRewardPlan(connection, planId);
        RewardPlanStatus status = deriveRewardPlanStatus(current.steps());
        boolean terminal = isTerminal(status);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_plans
                SET status = ?, completed_at = ?
                WHERE plan_id = ?
                """)) {
            statement.setString(1, status.name());
            if (terminal) {
                statement.setLong(2, statusTime.toEpochMilli());
            } else {
                statement.setNull(2, java.sql.Types.BIGINT);
            }
            statement.setString(3, planId.toString());
            statement.executeUpdate();
        }
        return requireRewardPlan(connection, planId);
    }

    private static RewardPlanStatus deriveRewardPlanStatus(List<RewardStepRecord> steps) {
        if (steps.isEmpty()) {
            return RewardPlanStatus.SUCCEEDED;
        }
        boolean pending = false;
        boolean dispatching = false;
        boolean succeeded = false;
        boolean failed = false;
        boolean uncertain = false;
        boolean skipped = false;
        for (RewardStepRecord step : steps) {
            switch (step.status()) {
                case PENDING -> pending = true;
                case DISPATCHING -> dispatching = true;
                case SUCCEEDED -> succeeded = true;
                case FAILED -> failed = true;
                case UNKNOWN -> uncertain = true;
                case SKIPPED -> skipped = true;
            }
        }
        if (uncertain) {
            return RewardPlanStatus.UNKNOWN;
        }
        if (dispatching || pending && (succeeded || failed || skipped)) {
            return RewardPlanStatus.IN_PROGRESS;
        }
        if (pending) {
            return RewardPlanStatus.PENDING;
        }
        if (succeeded && (failed || skipped)) {
            return RewardPlanStatus.PARTIAL;
        }
        if (failed) {
            return RewardPlanStatus.FAILED;
        }
        if (skipped) {
            return RewardPlanStatus.ABANDONED;
        }
        return RewardPlanStatus.SUCCEEDED;
    }

    private static boolean isTerminal(RewardPlanStatus status) {
        return status != RewardPlanStatus.PENDING && status != RewardPlanStatus.IN_PROGRESS;
    }

    private static boolean previousStepsSucceeded(RewardPlanRecord plan, int stepIndex) {
        for (RewardStepRecord step : plan.steps()) {
            if (step.index() >= stepIndex) {
                break;
            }
            if (step.status() != RewardStepStatus.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    private static RewardStepRecord findStep(RewardPlanRecord plan, int stepIndex)
            throws ScoreRepositoryException {
        return plan.steps().stream()
                .filter(step -> step.index() == stepIndex)
                .findFirst()
                .orElseThrow(() -> new ScoreRepositoryException(
                        "Unknown reward step " + stepIndex + " for plan " + plan.planId()));
    }

    private RewardPlanRecord requireRewardPlan(Connection connection, UUID planId)
            throws SQLException, ScoreRepositoryException {
        RewardPlanRecord plan = findRewardPlan(connection, planId);
        if (plan == null) {
            throw new ScoreRepositoryException("Unknown reward plan " + planId);
        }
        return plan;
    }

    private RewardPlanRecord findRewardPlanByAnyKey(
            Connection connection,
            RewardPlanRequest request) throws SQLException, ScoreRepositoryException {
        UUID found = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT plan_id FROM reward_plans
                WHERE plan_id = ? OR run_id = ? OR idempotency_key = ?
                """)) {
            statement.setString(1, request.planId().toString());
            statement.setString(2, request.runId().toString());
            statement.setString(3, request.idempotencyKey());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID candidate = UUID.fromString(result.getString("plan_id"));
                    if (found != null && !found.equals(candidate)) {
                        throw new ScoreRepositoryException(
                                "Reward idempotency keys resolve to conflicting plans");
                    }
                    found = candidate;
                }
            }
        }
        return found == null ? null : findRewardPlan(connection, found);
    }

    private RewardPlanRecord findRewardPlanByRunId(Connection connection, UUID runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT plan_id FROM reward_plans WHERE run_id = ?")) {
            statement.setString(1, runId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return findRewardPlan(
                        connection, UUID.fromString(result.getString("plan_id")));
            }
        }
    }

    private static void requireNoRewardTombstone(
            Connection connection,
            RewardPlanRequest request) throws SQLException, ScoreRepositoryException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM reward_tombstones
                WHERE plan_id = ? OR run_id = ? OR idempotency_key = ?
                LIMIT 1
                """)) {
            statement.setString(1, request.planId().toString());
            statement.setString(2, request.runId().toString());
            statement.setString(3, request.idempotencyKey());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ScoreRepositoryException(
                            "Reward plan was already finalized and pruned; replay is forbidden");
                }
            }
        }
    }

    private static void requireMatchingRewardPlan(
            RewardPlanRecord existing,
            RewardPlanRequest request) throws ScoreRepositoryException {
        boolean matches = existing.planId().equals(request.planId())
                && existing.runId().equals(request.runId())
                && existing.idempotencyKey().equals(request.idempotencyKey())
                && sameStoredInstant(existing.createdAt(), request.createdAt())
                && existing.steps().size() == request.steps().size();
        if (matches) {
            for (int index = 0; index < request.steps().size(); index++) {
                RewardStepRecord stored = existing.steps().get(index);
                RewardStepSpec requested = request.steps().get(index);
                if (stored.index() != index
                        || !stored.commandRoot().equals(requested.commandRoot())
                        || !stored.commandHash().equals(requested.commandHash())) {
                    matches = false;
                    break;
                }
            }
        }
        if (!matches) {
            throw new ScoreRepositoryException(
                    "Reward plan idempotency conflict for " + request.planId());
        }
    }

    private static boolean sameStoredInstant(Instant first, Instant second) {
        return first.toEpochMilli() == second.toEpochMilli();
    }

    private RewardPlanRecord findRewardPlan(Connection connection, UUID planId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT plan_id, run_id, idempotency_key, status,
                       created_at, started_at, completed_at
                FROM reward_plans WHERE plan_id = ?
                """)) {
            statement.setString(1, planId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new RewardPlanRecord(
                        UUID.fromString(result.getString("plan_id")),
                        UUID.fromString(result.getString("run_id")),
                        result.getString("idempotency_key"),
                        RewardPlanStatus.valueOf(result.getString("status")),
                        Instant.ofEpochMilli(result.getLong("created_at")),
                        optionalEpochMillis(result, "started_at"),
                        optionalEpochMillis(result, "completed_at"),
                        loadRewardSteps(connection, planId));
            }
        }
    }

    private List<RewardPlanRecord> loadRecentRewardPlans(
            Connection connection,
            RewardPlanStatus status,
            int limit) throws SQLException {
        List<UUID> planIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT plan_id FROM reward_plans
                WHERE status = ?
                ORDER BY created_at DESC, plan_id ASC
                LIMIT ?
                """)) {
            statement.setString(1, status.name());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    planIds.add(UUID.fromString(result.getString("plan_id")));
                }
            }
        }
        List<RewardPlanRecord> plans = new ArrayList<>(planIds.size());
        for (UUID planId : planIds) {
            plans.add(Objects.requireNonNull(findRewardPlan(connection, planId)));
        }
        return List.copyOf(plans);
    }

    private static List<RewardStepRecord> loadRewardSteps(
            Connection connection,
            UUID planId) throws SQLException {
        List<RewardStepRecord> steps = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT step_index, command_root, command_hash, status,
                       attempted_at, completed_at
                FROM reward_steps WHERE plan_id = ? ORDER BY step_index ASC
                """)) {
            statement.setString(1, planId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    steps.add(new RewardStepRecord(
                            result.getInt("step_index"),
                            result.getString("command_root"),
                            result.getString("command_hash"),
                            RewardStepStatus.valueOf(result.getString("status")),
                            optionalEpochMillis(result, "attempted_at"),
                            optionalEpochMillis(result, "completed_at")));
                }
            }
        }
        return List.copyOf(steps);
    }

    private RewardPlanRecord finalizeRewardPlan(
            Connection connection,
            UUID planId,
            Instant finalizedAt) throws SQLException, ScoreRepositoryException {
        RewardPlanRecord plan = requireRewardPlan(connection, planId);
        if (isTerminal(plan.status())) {
            return plan;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_steps
                SET status = CASE
                        WHEN status = 'DISPATCHING' THEN 'UNKNOWN'
                        WHEN status = 'PENDING' THEN 'SKIPPED'
                        ELSE status
                    END,
                    completed_at = CASE
                        WHEN status IN ('DISPATCHING', 'PENDING') THEN ?
                        ELSE completed_at
                    END
                WHERE plan_id = ? AND status IN ('DISPATCHING', 'PENDING')
                """)) {
            statement.setLong(1, finalizedAt.toEpochMilli());
            statement.setString(2, planId.toString());
            statement.executeUpdate();
        }
        return refreshRewardPlanStatus(connection, planId, finalizedAt);
    }

    private void recoverInterruptedRuns(Connection connection) throws SQLException {
        long recoveredAt = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE run_history
                SET status = 'UNKNOWN', ended_at = MAX(started_at, ?),
                    score = NULL, end_reason = 'PROCESS_INTERRUPTED'
                WHERE status = 'STARTED'
                """)) {
            statement.setLong(1, recoveredAt);
            int recovered = statement.executeUpdate();
            if (recovered > 0) {
                logger.warning(
                        "Marked " + recovered
                                + " interrupted run(s) UNKNOWN for staff inspection");
            }
        }
    }

    private void recoverInterruptedRewardPlans(
            Connection connection) throws SQLException, ScoreRepositoryException {
        List<UUID> interrupted = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT plan_id FROM reward_plans
                        WHERE status = 'IN_PROGRESS'
                        ORDER BY created_at ASC
                        """)) {
            while (result.next()) {
                interrupted.add(UUID.fromString(result.getString("plan_id")));
            }
        }
        if (interrupted.isEmpty()) {
            return;
        }

        Instant recoveredAt = Instant.ofEpochMilli(System.currentTimeMillis());
        for (UUID planId : interrupted) {
            finalizeRewardPlan(connection, planId, recoveredAt);
        }
        logger.warning(
                "Finalized " + interrupted.size()
                        + " interrupted in-progress reward plan(s) without replaying commands");
    }

    private <T> CompletableFuture<T> submitMutation(Callable<T> operation) {
        return submitOperation(operation, true);
    }

    private <T> CompletableFuture<T> submitRead(Callable<T> operation) {
        return submitOperation(operation, false);
    }

    private static long regularFileSize(Path path, boolean required) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("SQLite storage metadata contains a symbolic link");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (required) {
                throw new IOException("SQLite database is missing");
            }
            return 0L;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SQLite storage metadata is not a regular file");
        }
        return Files.size(path);
    }

    private static int countMigrationBackups(Path databasePath) throws IOException {
        Path parent = databasePath.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SQLite backup directory is unavailable");
        }
        String prefix = databasePath.getFileName() + ".pre-migration-v";
        Pattern automaticBackup = automaticMigrationBackupPattern(prefix);
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!automaticBackup.matcher(name).matches()) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                            "Automatic SQLite backup entry is not a safe regular file");
                }
                count = Math.addExact(count, 1);
            }
        }
        return count;
    }

    private static Pattern automaticMigrationBackupPattern(String prefix) {
        return Pattern.compile(
                Pattern.quote(prefix) + "(\\d+)-(\\d+)(?:-(\\d+))?\\.sqlite");
    }

    private static AutomaticMigrationBackup parseAutomaticMigrationBackup(
            Path path,
            Matcher matcher) throws IOException {
        try {
            int schemaVersion = Integer.parseInt(matcher.group(1));
            long createdAt = Long.parseLong(matcher.group(2));
            int suffix = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return new AutomaticMigrationBackup(path, schemaVersion, createdAt, suffix);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Automatic SQLite backup name contains an out-of-range number",
                    exception);
        }
    }

    private <T> CompletableFuture<T> submitOperation(
            Callable<T> operation,
            boolean mutation) {
        synchronized (lifecycleMonitor) {
            if (!initialized) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("The score repository is not initialized"));
            }
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("The score repository is closed"));
            }
            PendingMutation pending = mutation
                    ? new PendingMutation(UUID.randomUUID(), Instant.now())
                    : null;
            if (pending != null) {
                pendingMutations.add(pending);
                pendingMutationCount.incrementAndGet();
            }
            RepositoryOperation<T> accepted = new RepositoryOperation<>(
                    operation, mutation, pending);
            acceptedOperations.add(accepted);
            try {
                mutationExecutor.execute(accepted);
                return accepted.future();
            } catch (RejectedExecutionException exception) {
                accepted.rejectBeforeStart(exception);
                return accepted.future();
            }
        }
    }

    private record AutomaticMigrationBackup(
            Path path,
            int schemaVersion,
            long createdAt,
            int suffix) {}

    private void recordDatabaseFailure(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException) {
                lastDatabaseFailure = new SanitizedDatabaseFailure(
                        Instant.now(), "SQLITE", current.getClass().getSimpleName());
                return;
            }
            current = current.getCause();
        }
    }

    private <T> T mutate(DatabaseMutation<T> mutation) throws ScoreRepositoryException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = mutation.apply(connection);
                ScoreSnapshot nextSnapshot = loadSnapshot(connection);
                DurabilityState nextDurability = loadDurabilityState(connection);
                connection.commit();
                snapshot = nextSnapshot;
                durabilityState = nextDurability;
                return result;
            } catch (SQLException | ScoreRepositoryException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ScoreRepositoryException("Score database mutation failed", exception);
        }
    }

    private <T> T mutateWithRetry(
            String operationName,
            Object operationKey,
            DatabaseMutation<T> mutation) throws ScoreRepositoryException {
        Objects.requireNonNull(operationKey, "operationKey");
        for (int attempt = 1; attempt <= MAX_MUTATION_ATTEMPTS; attempt++) {
            try {
                return mutate(mutation);
            } catch (ScoreRepositoryException failure) {
                if (!isRetryableDatabaseFailure(failure)) {
                    throw failure;
                }
                if (attempt == MAX_MUTATION_ATTEMPTS) {
                    logger.log(
                            Level.SEVERE,
                            "Transient score database " + operationName + " for " + operationKey
                                    + " failed after " + MAX_MUTATION_ATTEMPTS
                                    + " attempts; no further retries will be made",
                            failure);
                    throw new ScoreRepositoryException(
                            "Score database " + operationName + " failed after "
                                    + MAX_MUTATION_ATTEMPTS + " attempts",
                            failure);
                }

                long delayMillis = INITIAL_RETRY_DELAY_MILLIS << (attempt - 1);
                logger.log(
                        Level.WARNING,
                        "Transient score database " + operationName + " for " + operationKey
                                + " failed on attempt " + attempt + '/' + MAX_MUTATION_ATTEMPTS
                                + "; retrying in " + delayMillis + " ms",
                        failure);
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ScoreRepositoryException(
                            "Score database " + operationName
                                    + " retry was interrupted after attempt " + attempt,
                            failure);
                }
            }
        }
        throw new AssertionError("Unreachable retry state");
    }

    static boolean isRetryableDatabaseFailure(Throwable failure) {
        if (failure == null) {
            return false;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sqlException) {
                if (isRetryableSqlException(sqlException)) {
                    return true;
                }
                SQLException next = sqlException.getNextException();
                if (next != null) {
                    pending.addLast(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.addLast(cause);
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean isRetryableSqlException(SQLException exception) {
        int sqlitePrimaryCode = exception.getErrorCode() & 0xFF;
        if (sqlitePrimaryCode == 5 || sqlitePrimaryCode == 6) {
            return true;
        }

        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("[sqlite_busy")
                || normalized.contains("[sqlite_locked")
                || normalized.contains("database is locked")
                || normalized.contains("database table is locked");
    }

    private ExistingScore findExistingScore(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT id, score FROM scoreboard WHERE " + SQLITE_IDENTITY_PREDICATE;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ExistingScore(result.getLong("id"), result.getInt("score"));
            }
        }
    }

    private static ExistingScore findExistingSeasonScore(
            Connection connection,
            UUID seasonId,
            UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, score FROM season_scores
                WHERE season_id = ? AND uuid = ? COLLATE NOCASE
                """)) {
            statement.setString(1, seasonId.toString());
            statement.setString(2, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ExistingScore(result.getLong("id"), result.getInt("score"));
            }
        }
    }

    private static ExistingScore findExistingCategoryScore(
            Connection connection,
            ScoreCategory category,
            UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, score FROM category_scores
                WHERE category = ? AND uuid = ? COLLATE NOCASE
                """)) {
            statement.setString(1, category.name());
            statement.setString(2, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ExistingScore(result.getLong("id"), result.getInt("score"));
            }
        }
    }

    private static Map<ScoreCategory, Integer> loadRunCategoryScores(
            Connection connection,
            UUID runId) throws SQLException, ScoreRepositoryException {
        EnumMap<ScoreCategory, Integer> scores = new EnumMap<>(ScoreCategory.class);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT category, score FROM run_category_scores WHERE run_id = ?
                """)) {
            statement.setString(1, runId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ScoreCategory category;
                    try {
                        category = ScoreCategory.parse(result.getString("category"));
                    } catch (IllegalArgumentException | NullPointerException exception) {
                        throw new ScoreRepositoryException(
                                "run_category_scores contains an invalid category", exception);
                    }
                    int score = result.getInt("score");
                    if (score < 1 || scores.putIfAbsent(category, score) != null) {
                        throw new ScoreRepositoryException(
                                "run_category_scores contains an invalid or duplicate score");
                    }
                }
            }
        }
        return Collections.unmodifiableMap(scores);
    }

    private RunRecord findRun(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.sequence, r.run_id, r.player_uuid, r.username, r.arena_id,
                       r.started_at, r.ended_at, r.score, r.end_reason, r.release,
                       r.season_id, r.status, p.plan_id
                FROM run_history r
                LEFT JOIN reward_plans p ON p.run_id = r.run_id
                WHERE r.run_id = ?
                """)) {
            statement.setString(1, runId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readRun(result) : null;
            }
        }
    }

    private static RunRecord readRun(ResultSet result) throws SQLException {
        Object rawScore = result.getObject("score");
        Integer score = rawScore == null ? null : ((Number) rawScore).intValue();
        String endReason = result.getString("end_reason");
        String seasonId = result.getString("season_id");
        String rewardPlanId = result.getString("plan_id");
        return new RunRecord(
                result.getLong("sequence"),
                UUID.fromString(result.getString("run_id")),
                UUID.fromString(result.getString("player_uuid")),
                result.getString("username"),
                result.getString("arena_id"),
                Instant.ofEpochMilli(result.getLong("started_at")),
                optionalEpochMillis(result, "ended_at"),
                Optional.ofNullable(score),
                Optional.ofNullable(endReason),
                result.getString("release"),
                seasonId == null ? Optional.empty() : Optional.of(UUID.fromString(seasonId)),
                rewardPlanId == null
                        ? Optional.empty()
                        : Optional.of(UUID.fromString(rewardPlanId)),
                RunStatus.valueOf(result.getString("status")));
    }

    private RetentionResult pruneRunHistory(Connection connection, int retainMostRecent)
            throws SQLException {
        return pruneRunHistory(connection, retainMostRecent, null);
    }

    private RetentionResult pruneRunHistory(
            Connection connection,
            int retainMostRecent,
            UUID protectedRunId) throws SQLException {
        validateRetentionLimit(retainMostRecent);
        long before = countRows(connection, "run_history");
        String deletableRuns = deletableRunSelectionSql(retainMostRecent);
        preserveTerminalRewardTombstones(
                connection, deletableRuns, protectedRunId, retainMostRecent);
        int deleted;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM run_history WHERE run_id IN (" + deletableRuns + ')')) {
            bindDeletableRunSelection(statement, 1, protectedRunId, retainMostRecent);
            deleted = statement.executeUpdate();
        }
        long after = countRows(connection, "run_history");
        if (before - after != deleted) {
            throw new SQLException("Run retention count changed unexpectedly");
        }
        return new RetentionResult(retainMostRecent, deleted, after);
    }

    private static String deletableRunSelectionSql(int retainMostRecent) {
        String retentionClause = retainMostRecent == 0
                ? ""
                : """
                        AND sequence NOT IN (
                            SELECT sequence FROM run_history
                            WHERE status NOT IN ('STARTED', 'UNKNOWN')
                            ORDER BY ended_at DESC, sequence DESC LIMIT ?
                        )
                        """;
        return """
                SELECT run_id FROM run_history
                WHERE status NOT IN ('STARTED', 'UNKNOWN')
                  AND (? IS NULL OR run_id <> ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM reward_plans
                      WHERE reward_plans.run_id = run_history.run_id
                        AND reward_plans.status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN')
                  )
                """ + retentionClause;
    }

    private static void preserveTerminalRewardTombstones(
            Connection connection,
            String deletableRuns,
            UUID protectedRunId,
            int retainMostRecent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_tombstones (
                    plan_id, run_id, idempotency_key, terminal_status,
                    completed_at, pruned_at
                )
                SELECT plan_id, run_id, idempotency_key, status,
                       COALESCE(completed_at, created_at), ?
                FROM reward_plans
                WHERE status IN ('SUCCEEDED', 'FAILED', 'PARTIAL', 'ABANDONED')
                  AND run_id IN (
                """ + deletableRuns + ")")) {
            statement.setLong(1, System.currentTimeMillis());
            bindDeletableRunSelection(statement, 2, protectedRunId, retainMostRecent);
            statement.executeUpdate();
        }
    }

    private static void bindDeletableRunSelection(
            PreparedStatement statement,
            int firstParameter,
            UUID runId,
            int retainMostRecent)
            throws SQLException {
        if (runId == null) {
            statement.setNull(firstParameter, java.sql.Types.VARCHAR);
            statement.setNull(firstParameter + 1, java.sql.Types.VARCHAR);
        } else {
            statement.setString(firstParameter, runId.toString());
            statement.setString(firstParameter + 1, runId.toString());
        }
        if (retainMostRecent > 0) {
            statement.setInt(firstParameter + 2, retainMostRecent);
        }
    }

    private static long countRows(Connection connection, String table) throws SQLException {
        if (!Set.of("run_history").contains(table)) {
            throw new IllegalArgumentException("Unsupported count table " + table);
        }
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (!result.next()) {
                throw new SQLException("COUNT returned no row for " + table);
            }
            return result.getLong(1);
        }
    }

    private static void validateRetentionLimit(int limit) {
        if (limit < 0 || limit > MAX_RUN_HISTORY_LIMIT) {
            throw new IllegalArgumentException(
                    "retainMostRecent must be between 0 and " + MAX_RUN_HISTORY_LIMIT);
        }
    }

    private static void validateStepIndex(int stepIndex) {
        if (stepIndex < 0 || stepIndex >= 100) {
            throw new IllegalArgumentException("stepIndex must be between 0 and 99");
        }
    }

    private static RewardStepStatus requireTerminalOutcome(RewardStepStatus outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome != RewardStepStatus.SUCCEEDED
                && outcome != RewardStepStatus.FAILED
                && outcome != RewardStepStatus.UNKNOWN) {
            throw new IllegalArgumentException(
                    "outcome must be SUCCEEDED, FAILED, or UNKNOWN");
        }
        return outcome;
    }

    private static RewardStepStatus requireUnknownResolution(RewardStepStatus resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (resolution != RewardStepStatus.SUCCEEDED
                && resolution != RewardStepStatus.FAILED
                && resolution != RewardStepStatus.SKIPPED) {
            throw new IllegalArgumentException(
                    "resolution must be SUCCEEDED, FAILED, or SKIPPED");
        }
        return resolution;
    }

    private static RunStatus requireInterruptedRunStatus(RunStatus status) {
        Objects.requireNonNull(status, "status");
        if (status != RunStatus.ABORTED && status != RunStatus.UNKNOWN) {
            throw new IllegalArgumentException("status must be ABORTED or UNKNOWN");
        }
        return status;
    }

    private static Optional<Instant> optionalEpochMillis(ResultSet result, String column)
            throws SQLException {
        Object rawValue = result.getObject(column);
        return rawValue == null
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(((Number) rawValue).longValue()));
    }

    private ScoreSnapshot loadSnapshot(Connection connection)
            throws SQLException, ScoreRepositoryException {
        List<RawScore> rawScores = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, score, typeof(score) AS score_storage_type, uuid, username, "
                                + "typeof(username) AS username_storage_type, updated_at "
                                + "FROM scoreboard")) {
            while (result.next()) {
                long id = result.getLong("id");
                int score = readStoredScore(result, "scoreboard", id);
                String storedUuid = result.getString("uuid");
                String username = readStoredUsername(result, "scoreboard", id);
                Optional<UUID> uuid = parseStoredUuid(id, storedUuid);
                Timestamp timestamp = result.getTimestamp("updated_at");
                rawScores.add(new RawScore(
                        id,
                        uuid,
                        username,
                        score,
                        timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant())));
            }
        }

        return buildScoreSnapshot(rawScores);
    }

    private ScoreSnapshot loadSeasonSnapshot(Connection connection, UUID seasonId)
            throws SQLException, ScoreRepositoryException {
        List<RawScore> rawScores = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, score, typeof(score) AS score_storage_type, uuid, username,
                       typeof(username) AS username_storage_type, updated_at
                FROM season_scores WHERE season_id = ?
                """)) {
            statement.setString(1, seasonId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong("id");
                    String username = readStoredUsername(result, "season_scores", id);
                    rawScores.add(new RawScore(
                            id,
                            parseStoredUuid(id, result.getString("uuid")),
                            username,
                            readStoredScore(result, "season_scores", id),
                            optionalEpochMillis(result, "updated_at")));
                }
            }
        }
        return buildScoreSnapshot(rawScores);
    }

    private DurabilityState loadDurabilityState(Connection connection)
            throws SQLException, ScoreRepositoryException {
        List<Season> seasons = loadSeasons(connection);
        Optional<Season> active = seasons.stream()
                .filter(season -> season.status() == SeasonStatus.ACTIVE)
                .findFirst();
        Optional<ScoreSnapshot> activeScores = active.isEmpty()
                ? Optional.empty()
                : Optional.of(loadSeasonSnapshot(connection, active.orElseThrow().id()));
        DurabilityMetrics metrics = new DurabilityMetrics(
                Instant.now(),
                countRows(connection, "run_history"),
                seasons.size(),
                active,
                loadPlanStatusCounts(connection),
                loadStepStatusCounts(connection),
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return new DurabilityState(
                metrics,
                seasons,
                activeScores,
                loadRecentRuns(connection),
                loadPlayerPreferences(connection),
                loadCategoryScoreSnapshots(connection));
    }

    private static Map<UUID, PlayerPreferences> loadPlayerPreferences(Connection connection)
            throws SQLException, ScoreRepositoryException {
        Map<UUID, PlayerPreferences> preferences = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT uuid, particle_mode, sounds_enabled, titles_enabled, updated_at
                        FROM player_preferences
                        """)) {
            while (result.next()) {
                UUID playerId = parseCanonicalUuid(
                        result.getString("uuid"), "player_preferences.uuid");
                PlayerPreferences value;
                try {
                    value = new PlayerPreferences(
                            playerId,
                            ParticlePreference.parse(result.getString("particle_mode")),
                            result.getInt("sounds_enabled") == 1,
                            result.getInt("titles_enabled") == 1,
                            Instant.ofEpochMilli(result.getLong("updated_at")));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw new ScoreRepositoryException(
                            "player_preferences contains an invalid preference row", exception);
                }
                if (preferences.putIfAbsent(playerId, value) != null) {
                    throw new ScoreRepositoryException(
                            "player_preferences contains a duplicate UUID");
                }
            }
        }
        return Map.copyOf(preferences);
    }

    private static Map<ScoreCategory, ScoreSnapshot> loadCategoryScoreSnapshots(
            Connection connection) throws SQLException, ScoreRepositoryException {
        EnumMap<ScoreCategory, ScoreSnapshot> snapshots = new EnumMap<>(ScoreCategory.class);
        for (ScoreCategory category : ScoreCategory.values()) {
            List<RawScore> rawScores = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, score, typeof(score) AS score_storage_type, uuid, username,
                           typeof(username) AS username_storage_type, updated_at
                    FROM category_scores
                    WHERE category = ?
                    """)) {
                statement.setString(1, category.name());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long id = result.getLong("id");
                        rawScores.add(new RawScore(
                                id,
                                parseStoredUuid(id, result.getString("uuid")),
                                readStoredUsername(result, "category_scores", id),
                                readStoredScore(result, "category_scores", id),
                                optionalEpochMillis(result, "updated_at")));
                    }
                }
            }
            snapshots.put(category, buildScoreSnapshot(rawScores));
        }
        return Collections.unmodifiableMap(snapshots);
    }

    private static List<Season> loadSeasons(Connection connection) throws SQLException {
        List<Season> seasons = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT id, name, status, created_at, transitioned_at,
                               activated_at, closed_at, archived_at
                        FROM seasons ORDER BY created_at DESC, id ASC
                        """)) {
            while (result.next()) {
                seasons.add(readSeason(result));
            }
        }
        return List.copyOf(seasons);
    }

    private static List<RunRecord> loadRecentRuns(Connection connection) throws SQLException {
        List<RunRecord> runs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.sequence, r.run_id, r.player_uuid, r.username, r.arena_id,
                       r.started_at, r.ended_at, r.score, r.end_reason, r.release,
                       r.season_id, r.status, p.plan_id
                FROM run_history r
                LEFT JOIN reward_plans p ON p.run_id = r.run_id
                ORDER BY r.sequence DESC LIMIT ?
                """)) {
            statement.setInt(1, RECENT_RUN_CACHE_LIMIT);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    runs.add(readRun(result));
                }
            }
        }
        return List.copyOf(runs);
    }

    private static List<RunInvestigationRecord> loadInvestigatedRuns(
            Connection connection,
            RunInvestigationQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT r.sequence, r.run_id, r.player_uuid, r.arena_id,
                       r.started_at, r.ended_at, r.score, r.end_reason, r.release,
                       r.season_id, r.status, p.plan_id,
                       p.status AS reward_plan_status
                FROM run_history r
                LEFT JOIN reward_plans p ON p.run_id = r.run_id
                WHERE 1 = 1
                """);
        if (query.status().isPresent()) {
            sql.append(" AND r.status = ?");
        }
        if (query.playerId().isPresent()) {
            sql.append(" AND r.player_uuid = ? COLLATE NOCASE");
        }
        if (query.runId().isPresent()) {
            sql.append(" AND r.run_id = ? COLLATE NOCASE");
        }
        if (query.arenaId().isPresent()) {
            sql.append(" AND r.arena_id = ?");
        }
        if (query.seasonId().isPresent()) {
            sql.append(" AND r.season_id = ? COLLATE NOCASE");
        }
        if (query.release().isPresent()) {
            sql.append(" AND r.release = ?");
        }
        if (query.startedAtInclusive().isPresent()) {
            sql.append(" AND r.started_at >= ? AND r.started_at < ?");
        }
        sql.append(" ORDER BY r.sequence DESC LIMIT ?");

        List<RunInvestigationRecord> runs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            if (query.status().isPresent()) {
                statement.setString(parameter++, query.status().orElseThrow().name());
            }
            if (query.playerId().isPresent()) {
                statement.setString(parameter++, query.playerId().orElseThrow().toString());
            }
            if (query.runId().isPresent()) {
                statement.setString(parameter++, query.runId().orElseThrow().toString());
            }
            if (query.arenaId().isPresent()) {
                statement.setString(parameter++, query.arenaId().orElseThrow());
            }
            if (query.seasonId().isPresent()) {
                statement.setString(parameter++, query.seasonId().orElseThrow().toString());
            }
            if (query.release().isPresent()) {
                statement.setString(parameter++, query.release().orElseThrow());
            }
            if (query.startedAtInclusive().isPresent()) {
                statement.setLong(
                        parameter++, query.startedAtInclusive().orElseThrow().toEpochMilli());
                statement.setLong(
                        parameter++, query.startedBeforeExclusive().orElseThrow().toEpochMilli());
            }
            statement.setInt(parameter, query.limit());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    runs.add(readInvestigatedRun(result));
                }
            }
        }
        return List.copyOf(runs);
    }

    private static RunInvestigationRecord readInvestigatedRun(ResultSet result)
            throws SQLException {
        Object rawScore = result.getObject("score");
        Integer score = rawScore == null ? null : ((Number) rawScore).intValue();
        String endReason = result.getString("end_reason");
        String seasonId = result.getString("season_id");
        String rewardPlanId = result.getString("plan_id");
        String rewardStatus = result.getString("reward_plan_status");
        return new RunInvestigationRecord(
                result.getLong("sequence"),
                UUID.fromString(result.getString("run_id")),
                UUID.fromString(result.getString("player_uuid")),
                result.getString("arena_id"),
                Instant.ofEpochMilli(result.getLong("started_at")),
                optionalEpochMillis(result, "ended_at"),
                Optional.ofNullable(score),
                Optional.ofNullable(endReason),
                result.getString("release"),
                seasonId == null ? Optional.empty() : Optional.of(UUID.fromString(seasonId)),
                RunStatus.valueOf(result.getString("status")),
                rewardPlanId == null
                        ? Optional.empty()
                        : Optional.of(UUID.fromString(rewardPlanId)),
                rewardStatus == null
                        ? Optional.empty()
                        : Optional.of(RewardPlanStatus.valueOf(rewardStatus)));
    }

    private static Map<RewardPlanStatus, Long> loadPlanStatusCounts(Connection connection)
            throws SQLException {
        Map<RewardPlanStatus, Long> counts = new java.util.EnumMap<>(RewardPlanStatus.class);
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT status, COUNT(*) AS total FROM reward_plans GROUP BY status")) {
            while (result.next()) {
                counts.put(
                        RewardPlanStatus.valueOf(result.getString("status")),
                        result.getLong("total"));
            }
        }
        return Map.copyOf(counts);
    }

    private static Map<RewardStepStatus, Long> loadStepStatusCounts(Connection connection)
            throws SQLException {
        Map<RewardStepStatus, Long> counts = new java.util.EnumMap<>(RewardStepStatus.class);
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT status, COUNT(*) AS total FROM reward_steps GROUP BY status")) {
            while (result.next()) {
                counts.put(
                        RewardStepStatus.valueOf(result.getString("status")),
                        result.getLong("total"));
            }
        }
        return Map.copyOf(counts);
    }

    private static ScoreSnapshot buildScoreSnapshot(List<RawScore> rawScores)
            throws ScoreRepositoryException {
        rawScores.sort(Comparator.comparingInt(RawScore::score)
                .reversed()
                .thenComparingLong(RawScore::id));

        List<ScoreEntry> entries = new ArrayList<>(rawScores.size());
        Map<UUID, PlayerStats> stats = new HashMap<>();
        int previousScore = 0;
        int currentRank = 0;
        for (int index = 0; index < rawScores.size(); index++) {
            RawScore raw = rawScores.get(index);
            if (index == 0 || raw.score() != previousScore) {
                currentRank = index + 1;
                previousScore = raw.score();
            }
            ScoreEntry entry = new ScoreEntry(
                    raw.id(), raw.uuid(), raw.username(), raw.score(), currentRank, raw.updatedAt());
            entries.add(entry);
            if (raw.uuid().isPresent()) {
                UUID uuid = raw.uuid().orElseThrow();
                PlayerStats previous = stats.put(uuid, new PlayerStats(
                        raw.id(),
                        uuid,
                        raw.username(),
                        raw.score(),
                        currentRank,
                        rawScores.size()));
                if (previous != null) {
                    throw new ScoreRepositoryException(
                            "Duplicate UUID reached the score snapshot: " + uuid);
                }
            }
        }
        return new ScoreSnapshot(Instant.now(), entries, stats);
    }

    private static void validateStoredIdentities(Connection connection)
            throws SQLException, ScoreRepositoryException {
        Map<UUID, Long> seen = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT id, uuid FROM scoreboard")) {
            while (result.next()) {
                long id = result.getLong("id");
                String text = result.getString("uuid");
                Optional<UUID> parsed = parseStoredUuid(id, text);
                if (parsed.isEmpty()) {
                    continue;
                }
                UUID uuid = parsed.orElseThrow();
                Long previousId = seen.putIfAbsent(uuid, id);
                if (previousId != null) {
                    throw new ScoreRepositoryException(
                            "Duplicate nonblank UUID " + uuid + " in scoreboard rows "
                                    + previousId + " and " + id);
                }
            }
        }
    }

    private static void validateStoredScoreboardRows(Connection connection)
            throws SQLException, ScoreRepositoryException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT id, score, typeof(score) AS score_storage_type,
                               username, typeof(username) AS username_storage_type
                        FROM scoreboard
                        """)) {
            while (result.next()) {
                long id = result.getLong("id");
                if (id < 0L) {
                    throw new ScoreRepositoryException(
                            "scoreboard row " + id + " has a negative id");
                }
                readStoredScore(result, "scoreboard", id);
                readStoredUsername(result, "scoreboard", id);
            }
        }
    }

    private static void validateStoredSeasonScoreRows(Connection connection)
            throws SQLException, ScoreRepositoryException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT id, score, typeof(score) AS score_storage_type,
                               username, typeof(username) AS username_storage_type
                        FROM season_scores
                        """)) {
            while (result.next()) {
                long id = result.getLong("id");
                if (id < 0L) {
                    throw new ScoreRepositoryException(
                            "season_scores row " + id + " has a negative id");
                }
                readStoredScore(result, "season_scores", id);
                readStoredUsername(result, "season_scores", id);
            }
        }
    }

    private static void validateStoredCategoryScoreRows(Connection connection)
            throws SQLException, ScoreRepositoryException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT id, category, uuid, score,
                               typeof(score) AS score_storage_type,
                               username, typeof(username) AS username_storage_type
                        FROM category_scores
                        """)) {
            while (result.next()) {
                long id = result.getLong("id");
                if (id < 0L) {
                    throw new ScoreRepositoryException(
                            "category_scores contains a negative id");
                }
                try {
                    ScoreCategory.parse(result.getString("category"));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw new ScoreRepositoryException(
                            "category_scores contains an invalid category", exception);
                }
                parseCanonicalUuid(result.getString("uuid"), "category_scores.uuid");
                if (readStoredScore(result, "category_scores", id) < 1) {
                    throw new ScoreRepositoryException(
                            "category_scores contains a non-positive score");
                }
                readStoredUsername(result, "category_scores", id);
            }
        }
    }

    private static void validateStoredPreferenceRows(Connection connection)
            throws SQLException, ScoreRepositoryException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT uuid, particle_mode, sounds_enabled, titles_enabled, updated_at
                        FROM player_preferences
                        """)) {
            while (result.next()) {
                parseCanonicalUuid(result.getString("uuid"), "player_preferences.uuid");
                try {
                    ParticlePreference.parse(result.getString("particle_mode"));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw new ScoreRepositoryException(
                            "player_preferences contains an invalid particle mode", exception);
                }
                if ((result.getInt("sounds_enabled") != 0
                                && result.getInt("sounds_enabled") != 1)
                        || (result.getInt("titles_enabled") != 0
                                && result.getInt("titles_enabled") != 1)
                        || result.getLong("updated_at") < 0L) {
                    throw new ScoreRepositoryException(
                            "player_preferences contains an invalid setting");
                }
            }
        }
    }

    private static int readStoredScore(ResultSet result, String table, long id)
            throws SQLException, ScoreRepositoryException {
        String storageType = result.getString("score_storage_type");
        long score = result.getLong("score");
        if (!"integer".equals(storageType)) {
            throw new ScoreRepositoryException(
                    table + " row " + id + " has a non-integer score");
        }
        if (score < 0L) {
            throw new ScoreRepositoryException(
                    table + " row " + id + " has a negative score");
        }
        if (score > Integer.MAX_VALUE) {
            throw new ScoreRepositoryException(
                    table + " row " + id + " has a score above " + Integer.MAX_VALUE);
        }
        return Math.toIntExact(score);
    }

    private static String readStoredUsername(ResultSet result, String table, long id)
            throws SQLException, ScoreRepositoryException {
        if (!"text".equals(result.getString("username_storage_type"))) {
            throw new ScoreRepositoryException(
                    table + " row " + id + " has a non-text username");
        }
        String username = result.getString("username");
        try {
            return validateUsername(username);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ScoreRepositoryException(
                    table + " row " + id + " has an invalid username: "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static Optional<UUID> parseStoredUuid(long id, String text)
            throws ScoreRepositoryException {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID uuid = UUID.fromString(text);
            if (text.length() != 36 || !uuid.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("UUID is not in canonical form");
            }
            return Optional.of(uuid);
        } catch (IllegalArgumentException exception) {
            throw new ScoreRepositoryException(
                    "Invalid nonblank UUID in scoreboard row " + id + ": " + text, exception);
        }
    }

    private static UUID parseCanonicalUuid(String text, String field)
            throws ScoreRepositoryException {
        try {
            UUID uuid = UUID.fromString(Objects.requireNonNull(text, field));
            if (text.length() != 36 || !uuid.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("UUID is not in canonical form");
            }
            return uuid;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ScoreRepositoryException(field + " contains an invalid UUID", exception);
        }
    }

    private static String validateUsername(String username) {
        return PersistenceValidation.playerName(username);
    }

    private static int sqliteUserVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) {
                throw new SQLException("PRAGMA user_version returned no row");
            }
            return result.getInt(1);
        }
    }

    private static boolean sqliteTableExists(Connection connection, String tableName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Map<String, ColumnInfo> sqliteColumns(Connection connection, String tableName)
            throws SQLException {
        Map<String, ColumnInfo> columns = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(" + tableName + ')')) {
            while (result.next()) {
                String name = result.getString("name").toLowerCase(Locale.ROOT);
                columns.put(name, new ColumnInfo(result.getInt("notnull") == 0));
            }
        }
        return Map.copyOf(columns);
    }

    private static boolean sqliteIndexExists(Connection connection, String indexName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean sqliteMigrationRecorded(Connection connection, int version)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean containsBlankUuid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT 1 FROM scoreboard WHERE uuid IS NOT NULL AND "
                                + SQLITE_TRIMMED_UUID + " = '' LIMIT 1")) {
            return result.next();
        }
    }

    private static boolean durabilitySchemaRequiresMigration(Connection connection)
            throws SQLException, ScoreRepositoryException {
        for (Map.Entry<String, Set<String>> table : durabilityTableColumns().entrySet()) {
            if (!sqliteTableExists(connection, table.getKey())) {
                return true;
            }
            validateDurabilityTable(connection, table.getKey(), table.getValue());
        }

        boolean missingIndex = false;
        Map<String, String> expectedIndexes = requiredIndexSql();
        for (String indexName : durabilityIndexNames()) {
            if (!sqliteIndexExists(connection, indexName)) {
                missingIndex = true;
            } else {
                requireIndexDefinition(connection, indexName, expectedIndexes.get(indexName));
            }
        }
        return missingIndex;
    }

    private static void validateExistingDurabilityTables(Connection connection)
            throws SQLException, ScoreRepositoryException {
        for (Map.Entry<String, Set<String>> table : durabilityTableColumns().entrySet()) {
            if (sqliteTableExists(connection, table.getKey())) {
                validateDurabilityTable(connection, table.getKey(), table.getValue());
            }
        }
    }

    private static void validateRequiredSchema(Connection connection)
            throws SQLException, ScoreRepositoryException {
        for (Map.Entry<String, Set<String>> table : durabilityTableColumns().entrySet()) {
            if (!sqliteTableExists(connection, table.getKey())) {
                throw new ScoreRepositoryException(
                        "SQLite schema is missing required table " + table.getKey());
            }
            validateDurabilityTable(connection, table.getKey(), table.getValue());
        }
        Map<String, String> expectedIndexes = requiredIndexSql();
        for (Map.Entry<String, String> index : expectedIndexes.entrySet()) {
            requireIndexDefinition(connection, index.getKey(), index.getValue());
        }
    }

    private static void validateExistingIndexDefinitions(Connection connection)
            throws SQLException, ScoreRepositoryException {
        for (Map.Entry<String, String> index : requiredIndexSql().entrySet()) {
            if (sqliteIndexExists(connection, index.getKey())) {
                requireIndexDefinition(connection, index.getKey(), index.getValue());
            }
        }
    }

    private static void validateDurabilityTable(
            Connection connection,
            String tableName,
            Set<String> requiredColumns) throws SQLException, ScoreRepositoryException {
        Map<String, ColumnInfo> columns = sqliteColumns(connection, tableName);
        requireColumns(columns, requiredColumns);
        Set<String> expectedNotNull = durabilityNotNullColumns().get(tableName);
        for (String column : expectedNotNull) {
            if (columns.get(column).nullable()) {
                throw new ScoreRepositoryException(
                        "Unsupported SQLite schema: " + tableName + '.' + column
                                + " must be NOT NULL");
            }
        }

        String tableSql = sqliteObjectSql(connection, "table", tableName)
                .orElseThrow(() -> new ScoreRepositoryException(
                        "SQLite schema is missing required table " + tableName));
        String normalizedSql = normalizeSchemaSql(tableSql);
        for (String fragment : durabilityTableSqlFragments().get(tableName)) {
            if (!normalizedSql.contains(normalizeSchemaSql(fragment))) {
                throw new ScoreRepositoryException(
                        "Unsupported SQLite schema: " + tableName
                                + " is missing a required key, unique, or CHECK constraint");
            }
        }

        Set<ForeignKeyInfo> expectedForeignKeys = durabilityForeignKeys().get(tableName);
        Set<ForeignKeyInfo> actualForeignKeys = sqliteForeignKeys(connection, tableName);
        if (!actualForeignKeys.equals(expectedForeignKeys)) {
            throw new ScoreRepositoryException(
                    "Unsupported SQLite schema: " + tableName
                            + " has unexpected foreign-key constraints");
        }
        if ("season_scores".equals(tableName)) {
            validateStoredSeasonScoreRows(connection);
        } else if ("category_scores".equals(tableName)) {
            validateStoredCategoryScoreRows(connection);
        } else if ("player_preferences".equals(tableName)) {
            validateStoredPreferenceRows(connection);
        }
    }

    private static void requireIndexDefinition(
            Connection connection,
            String indexName,
            String expectedSql) throws SQLException, ScoreRepositoryException {
        String actualSql = sqliteObjectSql(connection, "index", indexName)
                .orElseThrow(() -> new ScoreRepositoryException(
                        "SQLite schema is missing required index " + indexName));
        if (!normalizeSchemaSql(actualSql).equals(normalizeSchemaSql(expectedSql))) {
            throw new ScoreRepositoryException(
                    "Unsupported SQLite schema: index " + indexName
                            + " does not match its required definition");
        }
    }

    private static Optional<String> sqliteObjectSql(
            Connection connection,
            String type,
            String name) throws SQLException {
        if (!Set.of("table", "index").contains(type)) {
            throw new IllegalArgumentException("Unsupported SQLite object type " + type);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(result.getString("sql"));
            }
        }
    }

    private static Set<ForeignKeyInfo> sqliteForeignKeys(
            Connection connection,
            String tableName) throws SQLException {
        if (!durabilityTableColumns().containsKey(tableName)) {
            throw new IllegalArgumentException("Unsupported durability table " + tableName);
        }
        Set<ForeignKeyInfo> foreignKeys = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "PRAGMA foreign_key_list(" + tableName + ')')) {
            while (result.next()) {
                foreignKeys.add(new ForeignKeyInfo(
                        result.getString("table").toLowerCase(Locale.ROOT),
                        result.getString("from").toLowerCase(Locale.ROOT),
                        result.getString("to").toLowerCase(Locale.ROOT),
                        result.getString("on_delete").toLowerCase(Locale.ROOT),
                        result.getString("on_update").toLowerCase(Locale.ROOT)));
            }
        }
        return Set.copyOf(foreignKeys);
    }

    private static String normalizeSchemaSql(String sql) {
        String lower = Objects.requireNonNull(sql, "sql").toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (!Character.isWhitespace(character)
                    && character != '"'
                    && character != '`'
                    && character != '['
                    && character != ']') {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private static Map<String, Set<String>> durabilityTableColumns() {
        return Map.of(
                "seasons",
                Set.of(
                        "id",
                        "name",
                        "status",
                        "created_at",
                        "transitioned_at",
                        "activated_at",
                        "closed_at",
                        "archived_at"),
                "season_scores",
                Set.of("id", "season_id", "uuid", "username", "score", "updated_at"),
                "run_history",
                Set.of(
                        "sequence",
                        "run_id",
                        "player_uuid",
                        "username",
                        "arena_id",
                        "started_at",
                        "ended_at",
                        "score",
                        "end_reason",
                        "release",
                        "season_id",
                        "status"),
                "reward_plans",
                Set.of(
                        "plan_id",
                        "run_id",
                        "idempotency_key",
                        "status",
                        "created_at",
                        "started_at",
                        "completed_at"),
                "reward_steps",
                Set.of(
                        "plan_id",
                        "step_index",
                        "command_root",
                        "command_hash",
                        "status",
                        "attempted_at",
                        "completed_at"),
                "reward_tombstones",
                Set.of(
                        "plan_id",
                        "run_id",
                        "idempotency_key",
                        "terminal_status",
                        "completed_at",
                        "pruned_at"),
                "player_preferences",
                Set.of(
                        "uuid",
                        "particle_mode",
                        "sounds_enabled",
                        "titles_enabled",
                        "updated_at"),
                "category_scores",
                Set.of("id", "category", "uuid", "username", "score", "updated_at"),
                "run_category_scores",
                Set.of("run_id", "category", "score"));
    }

    private static Map<String, Set<String>> durabilityNotNullColumns() {
        return Map.of(
                "seasons",
                Set.of("name", "status", "created_at", "transitioned_at"),
                "season_scores",
                Set.of("season_id", "uuid", "username", "score", "updated_at"),
                "run_history",
                Set.of(
                        "run_id",
                        "player_uuid",
                        "username",
                        "arena_id",
                        "started_at",
                        "release",
                        "status"),
                "reward_plans",
                Set.of("run_id", "idempotency_key", "status", "created_at"),
                "reward_steps",
                Set.of(
                        "plan_id",
                        "step_index",
                        "command_root",
                        "command_hash",
                        "status"),
                "reward_tombstones",
                Set.of(
                        "run_id",
                        "idempotency_key",
                        "terminal_status",
                        "completed_at",
                        "pruned_at"),
                "player_preferences",
                Set.of(
                        "particle_mode",
                        "sounds_enabled",
                        "titles_enabled",
                        "updated_at"),
                "category_scores",
                Set.of("category", "uuid", "username", "score", "updated_at"),
                "run_category_scores",
                Set.of("run_id", "category", "score"));
    }

    private static Map<String, Set<String>> durabilityTableSqlFragments() {
        return Map.of(
                "seasons",
                Set.of(
                        "id VARCHAR(36) PRIMARY KEY",
                        "name VARCHAR(80) NOT NULL COLLATE NOCASE UNIQUE",
                        "CHECK (status IN ('PLANNED', 'ACTIVE', 'CLOSED', 'ARCHIVED'))"),
                "season_scores",
                Set.of(
                        "id INTEGER PRIMARY KEY AUTOINCREMENT",
                        "uuid VARCHAR(36) NOT NULL COLLATE NOCASE",
                        "score INTEGER NOT NULL DEFAULT 0 CHECK (score >= 0)",
                        "UNIQUE (season_id, uuid)"),
                "run_history",
                Set.of(
                        "sequence INTEGER PRIMARY KEY AUTOINCREMENT",
                        "run_id VARCHAR(36) NOT NULL UNIQUE",
                        "score INTEGER NULL CHECK (score IS NULL OR score >= 0)",
                        "CHECK (status IN ('STARTED', 'COMPLETED', 'ABORTED', 'UNKNOWN'))"),
                "reward_plans",
                Set.of(
                        "plan_id VARCHAR(36) PRIMARY KEY",
                        "run_id VARCHAR(36) NOT NULL UNIQUE",
                        "idempotency_key VARCHAR(128) NOT NULL UNIQUE",
                        "CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED',"
                                + " 'PARTIAL', 'UNKNOWN', 'ABANDONED'))"),
                "reward_steps",
                Set.of(
                        "step_index INTEGER NOT NULL CHECK (step_index >= 0)",
                        "PRIMARY KEY (plan_id, step_index)",
                        "CHECK (status IN ('PENDING', 'DISPATCHING', 'SUCCEEDED', 'FAILED',"
                                + " 'UNKNOWN', 'SKIPPED'))"),
                "reward_tombstones",
                Set.of(
                        "plan_id VARCHAR(36) PRIMARY KEY",
                        "run_id VARCHAR(36) NOT NULL UNIQUE",
                        "idempotency_key VARCHAR(128) NOT NULL UNIQUE",
                        "CHECK (terminal_status IN ('SUCCEEDED', 'FAILED', 'PARTIAL',"
                                + " 'ABANDONED'))"),
                "player_preferences",
                Set.of(
                        "uuid VARCHAR(36) PRIMARY KEY COLLATE NOCASE",
                        "CHECK (particle_mode IN ('FULL', 'REDUCED', 'OFF'))",
                        "CHECK (sounds_enabled IN (0, 1))",
                        "CHECK (titles_enabled IN (0, 1))"),
                "category_scores",
                Set.of(
                        "id INTEGER PRIMARY KEY AUTOINCREMENT",
                        "CHECK (category IN ('COMBO', 'FLAWLESS'))",
                        "score INTEGER NOT NULL CHECK (score >= 1)",
                        "UNIQUE (category, uuid)"),
                "run_category_scores",
                Set.of(
                        "CHECK (category IN ('COMBO', 'FLAWLESS'))",
                        "score INTEGER NOT NULL CHECK (score >= 1)",
                        "PRIMARY KEY (run_id, category)"));
    }

    private static Map<String, Set<ForeignKeyInfo>> durabilityForeignKeys() {
        return Map.of(
                "seasons",
                Set.of(),
                "season_scores",
                Set.of(new ForeignKeyInfo(
                        "seasons", "season_id", "id", "restrict", "no action")),
                "run_history",
                Set.of(new ForeignKeyInfo(
                        "seasons", "season_id", "id", "restrict", "no action")),
                "reward_plans",
                Set.of(new ForeignKeyInfo(
                        "run_history", "run_id", "run_id", "cascade", "no action")),
                "reward_steps",
                Set.of(new ForeignKeyInfo(
                        "reward_plans", "plan_id", "plan_id", "cascade", "no action")),
                "reward_tombstones",
                Set.of(),
                "player_preferences",
                Set.of(),
                "category_scores",
                Set.of(),
                "run_category_scores",
                Set.of(new ForeignKeyInfo(
                        "run_history", "run_id", "run_id", "cascade", "no action")));
    }

    private static Set<String> durabilityIndexNames() {
        return Set.of(
                ACTIVE_SEASON_INDEX,
                SEASON_RANKING_INDEX,
                RUN_RECENT_INDEX,
                RUN_PLAYER_INDEX,
                REWARD_PLAN_STATUS_INDEX,
                REWARD_STEP_STATUS_INDEX,
                CATEGORY_RANKING_INDEX);
    }

    private static Map<String, String> requiredIndexSql() {
        return Map.ofEntries(
                Map.entry(
                        UUID_INDEX,
                        "CREATE UNIQUE INDEX " + UUID_INDEX
                                + " ON scoreboard(uuid COLLATE NOCASE) WHERE "
                                + SQLITE_RESOLVED_UUID),
                Map.entry(
                        RANKING_INDEX,
                        "CREATE INDEX " + RANKING_INDEX
                                + " ON scoreboard(score DESC, id ASC)"),
                Map.entry(
                        ACTIVE_SEASON_INDEX,
                        "CREATE UNIQUE INDEX " + ACTIVE_SEASON_INDEX
                                + " ON seasons(status) WHERE status = 'ACTIVE'"),
                Map.entry(
                        SEASON_RANKING_INDEX,
                        "CREATE INDEX " + SEASON_RANKING_INDEX
                                + " ON season_scores(season_id, score DESC, id ASC)"),
                Map.entry(
                        RUN_RECENT_INDEX,
                        "CREATE INDEX " + RUN_RECENT_INDEX
                                + " ON run_history(sequence DESC)"),
                Map.entry(
                        RUN_PLAYER_INDEX,
                        "CREATE INDEX " + RUN_PLAYER_INDEX
                                + " ON run_history(player_uuid COLLATE NOCASE, sequence DESC)"),
                Map.entry(
                        REWARD_PLAN_STATUS_INDEX,
                        "CREATE INDEX " + REWARD_PLAN_STATUS_INDEX
                                + " ON reward_plans(status, created_at DESC)"),
                Map.entry(
                        REWARD_STEP_STATUS_INDEX,
                        "CREATE INDEX " + REWARD_STEP_STATUS_INDEX
                                + " ON reward_steps(status, plan_id)"),
                Map.entry(
                        CATEGORY_RANKING_INDEX,
                        "CREATE INDEX " + CATEGORY_RANKING_INDEX
                                + " ON category_scores(category, score DESC, id ASC)"));
    }

    private static void requireColumns(Map<String, ColumnInfo> columns, Set<String> required)
            throws ScoreRepositoryException {
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(columns.keySet());
        if (!missing.isEmpty()) {
            throw new ScoreRepositoryException(
                    "Unsupported SQLite table; missing columns: " + missing);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

    @FunctionalInterface
    private interface DatabaseMutation<T> {
        T apply(Connection connection) throws SQLException, ScoreRepositoryException;
    }

    private record ColumnInfo(boolean nullable) {
    }

    private record ForeignKeyInfo(
            String referencedTable,
            String fromColumn,
            String toColumn,
            String onDelete,
            String onUpdate) {
    }

    private record ExistingScore(long id, int score) {
    }

    private record PendingMutation(UUID id, Instant submittedAt) {
        private PendingMutation {
            Objects.requireNonNull(id, "id");
            submittedAt = PersistenceValidation.instant(submittedAt, "submittedAt");
        }
    }

    private final class RepositoryOperation<T> implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int SETTLED = 2;

        private final Callable<T> operation;
        private final boolean mutation;
        private final PendingMutation pending;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private RepositoryOperation(
                Callable<T> operation,
                boolean mutation,
                PendingMutation pending) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.mutation = mutation;
            this.pending = pending;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                return;
            }
            try {
                T result = operation.call();
                if (mutation) {
                    lastSuccessfulWriteAt = Instant.now();
                }
                future.complete(result);
            } catch (ScoreRepositoryException exception) {
                recordDatabaseFailure(exception);
                future.completeExceptionally(exception);
            } catch (Exception exception) {
                ScoreRepositoryException wrapped = new ScoreRepositoryException(
                        "Unexpected score repository operation failure", exception);
                recordDatabaseFailure(wrapped);
                future.completeExceptionally(wrapped);
            } catch (Error error) {
                future.completeExceptionally(error);
                throw error;
            } finally {
                state.set(SETTLED);
                cleanup();
            }
        }

        private CompletableFuture<T> future() {
            return future;
        }

        private void rejectBeforeStart(RejectedExecutionException failure) {
            if (!state.compareAndSet(QUEUED, SETTLED)) {
                return;
            }
            future.completeExceptionally(failure);
            cleanup();
        }

        private void cleanup() {
            acceptedOperations.remove(this);
            if (pending != null) {
                pendingMutations.remove(pending);
                pendingMutationCount.decrementAndGet();
            }
        }
    }

    private record RawScore(
            long id,
            Optional<UUID> uuid,
            String username,
            int score,
            Optional<Instant> updatedAt) {
    }

    private record DurabilityState(
            DurabilityMetrics metrics,
            List<Season> seasons,
            Optional<ScoreSnapshot> activeSeasonScores,
            List<RunRecord> recentRuns,
            Map<UUID, PlayerPreferences> preferences,
            Map<ScoreCategory, ScoreSnapshot> categoryScores) {

        private DurabilityState {
            Objects.requireNonNull(metrics, "metrics");
            seasons = List.copyOf(Objects.requireNonNull(seasons, "seasons"));
            activeSeasonScores = Objects.requireNonNull(
                    activeSeasonScores, "activeSeasonScores");
            recentRuns = List.copyOf(Objects.requireNonNull(recentRuns, "recentRuns"));
            preferences = Map.copyOf(Objects.requireNonNull(preferences, "preferences"));
            categoryScores = Map.copyOf(
                    Objects.requireNonNull(categoryScores, "categoryScores"));
        }

        private static DurabilityState empty() {
            return new DurabilityState(
                    DurabilityMetrics.empty(),
                    List.of(),
                    Optional.empty(),
                    List.of(),
                    Map.of(),
                    Map.of());
        }
    }
}
