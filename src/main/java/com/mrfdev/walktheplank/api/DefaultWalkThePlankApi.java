package com.mrfdev.walktheplank.api;

import com.mrfdev.walktheplank.build.BuildInfo;
import com.mrfdev.walktheplank.database.PlayerStats;
import com.mrfdev.walktheplank.database.ScoreEntry;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.database.RunRecord;
import com.mrfdev.walktheplank.database.Season;
import com.mrfdev.walktheplank.game.GameManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal service implementation; integrations should depend on {@link WalkThePlankApi}. */
public final class DefaultWalkThePlankApi implements WalkThePlankApi {
    private final BuildInfo buildInfo;
    private final ScoreRepository scores;
    private final GameManager games;

    public DefaultWalkThePlankApi(BuildInfo buildInfo, ScoreRepository scores, GameManager games) {
        this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.games = Objects.requireNonNull(games, "games");
    }

    @Override
    public String release() {
        return buildInfo.releaseLabel();
    }

    @Override
    public Optional<PlayerRecord> player(UUID playerId) {
        return scores.stats(Objects.requireNonNull(playerId, "playerId"))
                .map(DefaultWalkThePlankApi::playerRecord);
    }

    @Override
    public List<LeaderboardEntry> leaderboard(int limit) {
        if (limit < 0 || limit > 100) {
            throw new IllegalArgumentException("limit must be between zero and 100");
        }
        return scores.snapshot().top(limit).stream()
                .map(DefaultWalkThePlankApi::leaderboardEntry)
                .toList();
    }

    @Override
    public Optional<ActiveRun> activeRun(UUID playerId) {
        return games.sessionStatus(Objects.requireNonNull(playerId, "playerId"))
                .map(status -> new ActiveRun(
                        status.arenaId(),
                        status.score(),
                        status.elapsedSeconds(),
                        status.idleSeconds()));
    }

    @Override
    public Capacity capacity() {
        return new Capacity(
                games.activeSessions(),
                games.availableArenas(),
                games.quarantinedArenas(),
                games.totalArenas());
    }

    @Override
    public Optional<SeasonView> activeSeason() {
        return scores.activeSeason().map(DefaultWalkThePlankApi::seasonView);
    }

    @Override
    public QueueView queue(UUID playerId) {
        GameManager.QueueStatus status = games.queueStatus(
                Objects.requireNonNull(playerId, "playerId"));
        return new QueueView(
                status.enabled(),
                status.paused(),
                status.total(),
                status.playerPosition(),
                status.playerReadyUntil());
    }

    @Override
    public List<RunView> recentRuns(int limit) {
        if (limit < 0 || limit > 100) {
            throw new IllegalArgumentException("limit must be between zero and 100");
        }
        return scores.recentRuns(limit).stream()
                .map(DefaultWalkThePlankApi::runView)
                .toList();
    }

    private static PlayerRecord playerRecord(PlayerStats stats) {
        return new PlayerRecord(
                stats.uuid(),
                stats.username(),
                stats.bestScore(),
                stats.rank(),
                stats.totalEntries());
    }

    private static LeaderboardEntry leaderboardEntry(ScoreEntry entry) {
        return new LeaderboardEntry(
                entry.rank(),
                entry.uuid(),
                entry.username(),
                entry.score(),
                entry.updatedAt());
    }

    private static SeasonView seasonView(Season season) {
        return new SeasonView(season.id(), season.name(), season.status().name());
    }

    private static RunView runView(RunRecord run) {
        return new RunView(
                run.id(),
                run.playerId(),
                run.arenaId(),
                run.startedAt(),
                run.endedAt(),
                run.score(),
                run.status().name(),
                run.seasonId(),
                run.rewardPlanId());
    }
}
