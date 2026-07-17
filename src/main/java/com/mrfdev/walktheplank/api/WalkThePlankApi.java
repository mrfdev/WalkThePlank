package com.mrfdev.walktheplank.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only integration API registered with Bukkit's services manager.
 *
 * <p>Consumers should call this service on the primary server thread. Lifecycle integrations can
 * additionally listen for the events in {@code com.mrfdev.walktheplank.api.event}.</p>
 */
public interface WalkThePlankApi {
    String release();

    Optional<PlayerRecord> player(UUID playerId);

    List<LeaderboardEntry> leaderboard(int limit);

    Optional<ActiveRun> activeRun(UUID playerId);

    Capacity capacity();

    Optional<SeasonView> activeSeason();

    QueueView queue(UUID playerId);

    List<RunView> recentRuns(int limit);

    record PlayerRecord(UUID playerId, String lastKnownName, int personalBest, int rank, int totalPlayers) {
        public PlayerRecord {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(lastKnownName, "lastKnownName");
            if (personalBest < 0 || rank < 1 || totalPlayers < rank) {
                throw new IllegalArgumentException("invalid player record");
            }
        }
    }

    record LeaderboardEntry(
            int rank,
            Optional<UUID> playerId,
            String lastKnownName,
            int score,
            Optional<Instant> updatedAt) {
        public LeaderboardEntry {
            if (rank < 1 || score < 0) {
                throw new IllegalArgumentException("rank must be positive and score non-negative");
            }
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(lastKnownName, "lastKnownName");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record ActiveRun(String arenaId, int score, long elapsedSeconds, long idleSeconds) {
        public ActiveRun {
            Objects.requireNonNull(arenaId, "arenaId");
            if (score < 0 || elapsedSeconds < 0L || idleSeconds < 0L) {
                throw new IllegalArgumentException("run counters cannot be negative");
            }
        }
    }

    record Capacity(int active, int available, int quarantined, int configured) {
        public Capacity {
            if (active < 0 || available < 0 || quarantined < 0 || configured < 0) {
                throw new IllegalArgumentException("invalid arena capacity");
            }
        }
    }

    record SeasonView(UUID id, String name, String status) {
        public SeasonView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
        }
    }

    record QueueView(
            boolean enabled,
            boolean paused,
            int total,
            int position,
            Optional<Instant> readyUntil) {
        public QueueView {
            if (total < 0 || position < 0 || position > total) {
                throw new IllegalArgumentException("invalid queue counters");
            }
            Objects.requireNonNull(readyUntil, "readyUntil");
        }
    }

    record RunView(
            UUID runId,
            UUID playerId,
            String arenaId,
            Instant startedAt,
            Optional<Instant> endedAt,
            Optional<Integer> score,
            String status,
            Optional<UUID> seasonId,
            Optional<UUID> rewardPlanId) {
        public RunView {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(arenaId, "arenaId");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endedAt, "endedAt");
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(seasonId, "seasonId");
            Objects.requireNonNull(rewardPlanId, "rewardPlanId");
        }
    }
}
