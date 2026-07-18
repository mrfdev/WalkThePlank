package com.mrfdev.walktheplank.placeholder;

import com.mrfdev.walktheplank.database.PlayerStats;
import com.mrfdev.walktheplank.database.ScoreEntry;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.database.ScoreCategory;
import com.mrfdev.walktheplank.game.GameManager;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class InfinityParkourExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final ScoreRepository scores;
    private final GameManager games;

    public InfinityParkourExpansion(JavaPlugin plugin, ScoreRepository scores, GameManager games) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.games = Objects.requireNonNull(games, "games");
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getIdentifier() {
        return "infinityparkour";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String parameters) {
        String normalized = parameters.toLowerCase(Locale.ROOT);
        GameManager.PlaceholderSnapshot gameplay = games.placeholderSnapshot(
                player == null ? null : player.getUniqueId());
        if (normalized.equals("version")) {
            return getVersion();
        }
        if (normalized.equals("total_players")) {
            return Integer.toString(scores.snapshot().totalEntries());
        }
        if (normalized.equals("season_total_players")) {
            return Integer.toString(scores.activeSeasonScores()
                    .map(scoreboard -> scoreboard.totalEntries())
                    .orElse(0));
        }
        if (normalized.equals("active_season_id")) {
            return scores.activeSeason().map(season -> season.id().toString()).orElse("");
        }
        if (normalized.equals("active_season_name")) {
            return scores.activeSeason().map(season -> season.name()).orElse("");
        }
        String queue = queuePlaceholder(normalized, gameplay);
        if (queue != null) {
            return queue;
        }
        String capacity = capacityPlaceholder(normalized, gameplay);
        if (capacity != null) {
            return capacity;
        }
        if (normalized.startsWith("season_top_")) {
            return topPlaceholder(
                    normalized.substring("season_".length()),
                    scores.currentSeasonTop(10));
        }
        if (normalized.startsWith("combo_top_")) {
            return topPlaceholder(
                    normalized.substring("combo_".length()),
                    scores.categoryTop(ScoreCategory.COMBO, 10));
        }
        if (normalized.startsWith("flawless_top_")) {
            return topPlaceholder(
                    normalized.substring("flawless_".length()),
                    scores.categoryTop(ScoreCategory.FLAWLESS, 10));
        }
        if (normalized.startsWith("top_")) {
            return topPlaceholder(normalized, scores.top());
        }
        if (player == null) {
            return null;
        }

        if (normalized.equals("in_game")) {
            return Boolean.toString(gameplay.session().isPresent());
        }
        if (normalized.equals("current_score")) {
            return Integer.toString(gameplay.session().map(GameManager.SessionStatus::score).orElse(0));
        }
        if (normalized.equals("current_combo")) {
            return Integer.toString(gameplay.session()
                    .map(GameManager.SessionStatus::combo)
                    .orElse(0));
        }
        if (normalized.equals("maximum_combo")) {
            return Integer.toString(gameplay.session()
                    .map(GameManager.SessionStatus::maximumCombo)
                    .orElse(0));
        }
        if (normalized.equals("current_flawless")) {
            return Boolean.toString(gameplay.session()
                    .map(GameManager.SessionStatus::flawless)
                    .orElse(false));
        }
        if (normalized.equals("queue_position")) {
            return Integer.toString(gameplay.playerQueuePosition());
        }
        if (normalized.equals("queue_ready")) {
            return Boolean.toString(gameplay.playerQueueReady());
        }

        GameManager.SessionStatus activeRun = gameplay.session().orElse(null);
        if (normalized.equals("current_arena")) {
            return activeRun == null ? "" : activeRun.arenaId();
        }
        if (normalized.equals("elapsed_seconds")) {
            return Long.toString(activeRun == null ? 0L : activeRun.elapsedSeconds());
        }
        if (normalized.equals("idle_seconds")) {
            return Long.toString(activeRun == null ? 0L : activeRun.idleSeconds());
        }

        PlayerStats stats = scores.stats(player.getUniqueId()).orElse(null);
        PlayerStats seasonStats = scores.currentSeasonStats(player.getUniqueId()).orElse(null);
        PlayerStats comboStats =
                scores.categoryStats(ScoreCategory.COMBO, player.getUniqueId()).orElse(null);
        PlayerStats flawlessStats =
                scores.categoryStats(ScoreCategory.FLAWLESS, player.getUniqueId()).orElse(null);
        var preferences = scores.preferences(player.getUniqueId());
        return switch (normalized) {
            case "score" -> Integer.toString(stats == null ? 0 : stats.bestScore());
            case "previous_best" -> Integer.toString(stats == null ? 0 : stats.bestScore());
            case "best_delta" -> Integer.toString(Math.max(
                    0,
                    gameplay.session().map(GameManager.SessionStatus::score).orElse(0)
                            - (stats == null ? 0 : stats.bestScore())));
            case "rank" -> Integer.toString(stats == null ? 0 : stats.rank());
            case "percentile" -> Integer.toString(stats == null
                    ? 0
                    : Math.max(1, (int) Math.ceil(stats.rank() * 100.0 / stats.totalEntries())));
            case "season_score" -> Integer.toString(seasonStats == null ? 0 : seasonStats.bestScore());
            case "season_rank" -> Integer.toString(seasonStats == null ? 0 : seasonStats.rank());
            case "season_percentile" -> Integer.toString(seasonStats == null
                    ? 0
                    : Math.max(1, (int) Math.ceil(
                            seasonStats.rank() * 100.0 / seasonStats.totalEntries())));
            case "combo_score" -> Integer.toString(
                    comboStats == null ? 0 : comboStats.bestScore());
            case "combo_rank" -> Integer.toString(
                    comboStats == null ? 0 : comboStats.rank());
            case "flawless_score" -> Integer.toString(
                    flawlessStats == null ? 0 : flawlessStats.bestScore());
            case "flawless_rank" -> Integer.toString(
                    flawlessStats == null ? 0 : flawlessStats.rank());
            case "particles" -> preferences.particles().name().toLowerCase(Locale.ROOT);
            case "sounds" -> Boolean.toString(preferences.soundsEnabled());
            case "titles" -> Boolean.toString(preferences.titlesEnabled());
            default -> null;
        };
    }

    private String queuePlaceholder(
            String parameters,
            GameManager.PlaceholderSnapshot gameplay) {
        GameManager.QueueStatus status = gameplay.queue();
        return switch (parameters) {
            case "queue_enabled" -> Boolean.toString(status.enabled());
            case "queue_paused" -> Boolean.toString(status.paused());
            case "queue_total" -> Integer.toString(status.total());
            case "queue_waiting" -> Integer.toString(status.waiting());
            case "queue_ready_count" -> Integer.toString(status.ready());
            default -> null;
        };
    }

    private String capacityPlaceholder(
            String parameters,
            GameManager.PlaceholderSnapshot gameplay) {
        return switch (parameters) {
            case "active_arenas" -> Integer.toString(gameplay.activeArenas());
            case "available_arenas" -> Integer.toString(gameplay.availableArenas());
            case "total_arenas" -> Integer.toString(gameplay.totalArenas());
            case "quarantined_arenas" -> Integer.toString(gameplay.quarantinedArenas());
            default -> null;
        };
    }

    private String topPlaceholder(String parameters, List<ScoreEntry> top) {
        String[] parts = parameters.split("_");
        if (parts.length != 3) {
            return null;
        }
        int position;
        try {
            position = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (position < 1 || position > top.size()) {
            return "";
        }
        ScoreEntry entry = top.get(position - 1);
        return switch (parts[2]) {
            case "name" -> entry.username();
            case "score" -> Integer.toString(entry.score());
            case "rank" -> Integer.toString(entry.rank());
            default -> null;
        };
    }
}
