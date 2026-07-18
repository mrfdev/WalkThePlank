package com.mrfdev.walktheplank.database;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persistent score storage with lock-free reads from an immutable snapshot. */
public interface ScoreRepository extends AutoCloseable {
    /** Performs schema validation/migration and synchronously loads the initial snapshot. */
    void initialize() throws ScoreRepositoryException;

    /** Refreshes the last known name only when this UUID already has a scoreboard row. */
    CompletableFuture<Void> touchIdentity(UUID uuid, String username);

    /** Records a score while retaining the player's personal best. */
    CompletableFuture<ScoreUpdateResult> recordScore(UUID uuid, String username, int score);

    /** Atomically retains one idempotent run and updates all-time and active-season bests. */
    CompletableFuture<CompletedRunResult> recordCompletedRun(CompletedRun run);

    /** Persists a run before gameplay and captures the then-active season atomically. */
    CompletableFuture<RunRecord> startRun(RunStart run);

    /** Idempotently completes a persisted start and projects its score. */
    CompletableFuture<CompletedRunResult> completeRun(RunCompletion completion);

    /**
     * Atomically completes a run, projects its score, and persists an optional redacted reward
     * plan. Exact retries verify both shapes but never automatically replay a committed plan.
     */
    CompletableFuture<CompletedRunWithRewardPlanResult> completeRunWithRewardPlan(
            RunCompletion completion,
            Optional<RewardPlanRequest> rewardPlan);

    /** Marks an unfinished run ABORTED or UNKNOWN without projecting a score. */
    CompletableFuture<RunRecord> markRunInterrupted(
            UUID runId, RunStatus status, Instant endedAt, String reason);

    /** Creates a planned season. Creation never implicitly activates it. */
    CompletableFuture<Season> createSeason(UUID id, String name, Instant createdAt);

    /** Activates a planned season only when no other season is active. */
    CompletableFuture<Season> activateSeason(UUID id, Instant activatedAt);

    /** Explicitly closes a planned or active season. */
    CompletableFuture<Season> closeSeason(UUID id, Instant closedAt);

    /** Reopens a closed season as planned; activation remains a separate explicit step. */
    CompletableFuture<Season> reopenSeason(UUID id, Instant reopenedAt);

    /** Archives a closed season. Archived seasons cannot be reopened or activated. */
    CompletableFuture<Season> archiveSeason(UUID id, Instant archivedAt);

    /** Seasons in newest-created-first order. */
    List<Season> seasons();

    /** The active season, when one exists. */
    Optional<Season> activeSeason();

    /** Current active-season scores from the same lock-free durability snapshot. */
    Optional<ScoreSnapshot> activeSeasonScores();

    default List<ScoreEntry> currentSeasonTop(int limit) {
        return activeSeasonScores().map(scores -> scores.top(limit)).orElseGet(List::of);
    }

    default Optional<PlayerStats> currentSeasonStats(UUID uuid) {
        return activeSeasonScores().flatMap(scores -> scores.stats(uuid));
    }

    /** Loads a historical season score view on the repository executor. */
    CompletableFuture<ScoreSnapshot> seasonScores(UUID seasonId);

    /** Retained runs in newest-recorded-first order, capped by the repository cache. */
    List<RunRecord> recentRuns(int limit);

    /**
     * Queries redacted retained-run evidence using UUID ownership and bounded prepared filters.
     * Results are newest-recorded-first and capped at 100 by the immutable query.
     */
    CompletableFuture<List<RunInvestigationRecord>> investigateRuns(
            RunInvestigationQuery query);

    /** Loads one redacted retained run by its exact UUID, without name-based ownership. */
    CompletableFuture<Optional<RunInvestigationRecord>> run(UUID runId);

    /** Configuration-independent automatic run-history retention bound. */
    int runHistoryRetentionLimit();

    /** Applies a retention bound while preserving STARTED runs and unsettled reward ledgers. */
    CompletableFuture<RetentionResult> pruneRunHistory(int retainMostRecent);

    /** Creates or exactly resumes a PENDING reward plan before atomic step claims. */
    CompletableFuture<RewardPlanBeginResult> prepareRewardPlan(RewardPlanRequest request);

    /** Commits DISPATCHING before the associated command is handed to the server. */
    CompletableFuture<RewardStepDispatchResult> claimRewardStep(
            UUID planId, int stepIndex, Instant attemptedAt);

    /** Records a terminal step outcome and derives a terminal/aggregate plan status. */
    CompletableFuture<RewardPlanRecord> recordRewardStepOutcome(
            UUID planId,
            int stepIndex,
            RewardStepStatus outcome,
            Instant completedAt);

    /** Resolves one UNKNOWN step without replay; only SUCCEEDED, FAILED, or SKIPPED is accepted. */
    CompletableFuture<RewardPlanRecord> resolveUnknownRewardStep(
            UUID planId,
            int stepIndex,
            RewardStepStatus resolution,
            Instant resolvedAt);

    /** Freezes every remaining step and derives a terminal plan state without replaying it. */
    CompletableFuture<RewardPlanRecord> finalizeRewardPlan(UUID planId, Instant finalizedAt);

    /** Loads one reward plan without exposing raw command arguments. */
    CompletableFuture<Optional<RewardPlanRecord>> rewardPlan(UUID planId);

    /** Loads 1..100 redacted reward plans with the requested status, newest first. */
    CompletableFuture<List<RewardPlanRecord>> recentRewardPlans(
            RewardPlanStatus status, int limit);

    /** Lock-free operational durability counts. */
    DurabilityMetrics durabilityMetrics();

    /**
     * Runs a read-only SQLite integrity and storage probe on the repository executor.
     *
     * <p>The returned report contains counts and byte sizes only; it never exposes a path, SQL
     * text, player identity, or reward command.</p>
     */
    CompletableFuture<DatabaseDoctorReport> inspectDatabase();

    /** Returns the current immutable in-memory snapshot without database I/O. */
    ScoreSnapshot snapshot();

    default Optional<PlayerStats> stats(UUID uuid) {
        return snapshot().stats(uuid);
    }

    default List<ScoreEntry> top() {
        return snapshot().top();
    }

    /** Lock-free UUID-owned accessibility preferences; absent rows use safe defaults. */
    PlayerPreferences preferences(UUID playerId);

    /** Persists one complete preference state on the repository writer. */
    CompletableFuture<PlayerPreferences> updatePreferences(PlayerPreferences preferences);

    /** Atomically changes only the particle preference, preserving concurrent field updates. */
    default CompletableFuture<PlayerPreferences> updateParticlePreference(
            UUID playerId,
            ParticlePreference preference,
            Instant changedAt) {
        return updatePreferences(
                preferences(playerId).withParticles(preference, changedAt));
    }

    /** Atomically changes only the sound preference, preserving concurrent field updates. */
    default CompletableFuture<PlayerPreferences> updateSoundPreference(
            UUID playerId,
            boolean enabled,
            Instant changedAt) {
        return updatePreferences(
                preferences(playerId).withSounds(enabled, changedAt));
    }

    /** Atomically changes only the title preference, preserving concurrent field updates. */
    default CompletableFuture<PlayerPreferences> updateTitlePreference(
            UUID playerId,
            boolean enabled,
            Instant changedAt) {
        return updatePreferences(
                preferences(playerId).withTitles(enabled, changedAt));
    }

    /** Immutable category leaderboard that never changes the historical Classic snapshot. */
    ScoreSnapshot categoryScores(ScoreCategory category);

    default List<ScoreEntry> categoryTop(ScoreCategory category, int limit) {
        return categoryScores(category).top(limit);
    }

    default Optional<PlayerStats> categoryStats(ScoreCategory category, UUID playerId) {
        return categoryScores(category).stats(playerId);
    }

    /** All category publications from the same last committed repository state. */
    Map<ScoreCategory, ScoreSnapshot> categoryScoreSnapshots();

    /** The verified pre-migration SQLite backup created by this instance, when any. */
    Optional<Path> migrationBackup();

    /**
     * Stops accepting work and drains already accepted operations for at most the given duration.
     * Queued operations are completed exceptionally when the timeout expires.
     *
     * @return {@code true} when every accepted operation drained before the deadline
     */
    boolean close(Duration timeout);

    /** Drains for at most five seconds. */
    @Override
    default void close() {
        close(Duration.ofSeconds(5));
    }
}
