package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.database.ScoreSnapshot;
import com.mrfdev.walktheplank.database.Season;
import com.mrfdev.walktheplank.export.LeaderboardExportService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;

/** Explicit season state transitions and UUID-only leaderboard exports. */
final class SeasonCommands {
    enum Transition {
        ACTIVATE("activated"),
        CLOSE("closed"),
        REOPEN("reopened as planned"),
        ARCHIVE("archived");

        private final String verb;

        Transition(String verb) {
            this.verb = verb;
        }

        String verb() {
            return verb;
        }
    }

    private final CommandSupport support;
    private final LeaderboardExportService exports;

    SeasonCommands(CommandSupport support) {
        this.support = support;
        this.exports = new LeaderboardExportService(
                support.plugin.getDataFolder().toPath());
    }

    void list(CommandSender sender) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminSeason())) {
            return;
        }
        support.sendHeader(sender, "Event seasons");
        List<Season> seasons = support.scores.seasons();
        if (seasons.isEmpty()) {
            support.sendLine(
                    sender,
                    "&7No seasons have been created. All-time scores remain active.");
            return;
        }
        for (Season season : seasons) {
            support.sendLine(
                    sender,
                    "&3{{seasonId}} &7- &f{{seasonName}} &7({{status}}, changed {{changedAt}})",
                    Map.of(
                            "seasonId", season.id(),
                            "seasonName", season.name(),
                            "status", season.status(),
                            "changedAt",
                            CommandSupport.SNAPSHOT_TIME.format(
                                    season.transitionedAt())));
        }
    }

    void create(CommandSender sender, String name) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminSeason())) {
            return;
        }
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > 80) {
            support.sendLine(
                    sender,
                    "&cSeason names must contain between 1 and 80 characters.");
            return;
        }
        reportMutation(
                sender,
                "created",
                support.scores.createSeason(
                        UUID.randomUUID(), normalized, Instant.now()));
    }

    void transition(CommandSender sender, Transition transition, UUID seasonId) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminSeason())) {
            return;
        }
        Instant now = Instant.now();
        CompletableFuture<Season> mutation = switch (transition) {
            case ACTIVATE -> support.scores.activateSeason(seasonId, now);
            case CLOSE -> support.scores.closeSeason(seasonId, now);
            case REOPEN -> support.scores.reopenSeason(seasonId, now);
            case ARCHIVE -> support.scores.archiveSeason(seasonId, now);
        };
        reportMutation(sender, transition.verb(), mutation);
    }

    void exportAllTime(CommandSender sender) {
        if (authorizedForExport(sender)) {
            exportSnapshot(sender, support.scores.snapshot(), "all-time");
        }
    }

    void exportCurrentSeason(CommandSender sender) {
        if (!authorizedForExport(sender)) {
            return;
        }
        ScoreSnapshot current = support.scores.activeSeasonScores().orElse(null);
        if (current == null) {
            support.sendLine(sender, "&eThere is no active season to export.");
            return;
        }
        exportSnapshot(sender, current, "current-season");
    }

    void exportSeason(CommandSender sender, UUID seasonId) {
        if (!authorizedForExport(sender)) {
            return;
        }
        support.completeOnMainThread(
                support.scores.seasonScores(seasonId),
                snapshot -> exportSnapshot(
                        sender, snapshot, "season-" + seasonId),
                "season export lookup",
                sender);
    }

    private void reportMutation(
            CommandSender sender,
            String verb,
            CompletableFuture<Season> mutation) {
        support.completeOnMainThread(
                mutation,
                season -> {
                    support.games.auditSeasonTransition(
                            CommandSupport.operatorId(sender), season);
                    support.sendLine(
                            sender,
                            "&aSeason &f{{seasonName}} &a{{verb}}. ID: &f{{seasonId}}",
                            Map.of(
                                    "seasonName", season.name(),
                                    "verb", verb,
                                    "seasonId", season.id()));
                },
                "season " + verb,
                sender);
    }

    private boolean authorizedForExport(CommandSender sender) {
        return support.requireAdministrativePermission(
                sender, support.permissions().adminExport());
    }

    private void exportSnapshot(
            CommandSender sender,
            ScoreSnapshot snapshot,
            String category) {
        support.sendLine(
                sender,
                "&7Writing a UUID-only leaderboard snapshot...");
        UUID requestingOperator = CommandSupport.operatorId(sender);
        support.operations.submitRequired(() -> {
                    if (!support.awaitMainApproval(() -> support.stillAuthorized(
                            sender, support.permissions().adminExport()))) {
                        throw new SecurityException(
                                "Leaderboard export authorization changed before execution");
                    }
                    return exports.export(
                            snapshot.scores(), category, snapshot.capturedAt());
                })
                .whenComplete((result, failure) -> {
                    if (failure == null) {
                        support.games.auditLeaderboardExport(
                                requestingOperator, category, result.rows());
                        support.scheduleCommandReply(sender, () -> {
                            support.sendLine(
                                    sender,
                                    "&aExported &f{{rows}}&a row(s).",
                                    Map.of("rows", result.rows()));
                            support.sendField(
                                    sender, "CSV", result.csvFileName());
                            support.sendField(
                                    sender, "JSON", result.jsonFileName());
                        });
                        return;
                    }
                    support.plugin.getLogger().log(
                            Level.SEVERE,
                            "Could not export the leaderboard",
                            failure);
                    support.scheduleCommandReply(
                            sender,
                            () -> support.sendLine(
                                    sender,
                                    "&cThe UUID-only export failed safely; "
                                            + "see the console for its error category."));
                });
    }
}
