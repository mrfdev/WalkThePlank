package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RunInvestigationQuery;
import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import com.mrfdev.walktheplank.database.RunStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;

/** Privacy-safe retained-run investigation by immutable identifiers. */
final class InvestigationCommands {
    private final CommandSupport support;

    InvestigationCommands(CommandSupport support) {
        this.support = support;
    }

    void help(CommandSender sender) {
        if (!authorized(sender)) {
            return;
        }
        support.sendHeader(sender, "Retained-run investigation");
        support.sendCommandHelp(
                sender,
                "/walk admin run list [status|all] [limit]",
                "List newest retained UUID-owned run evidence");
        support.sendCommandHelp(
                sender,
                "/walk admin run inspect <run-uuid>",
                "Inspect one exact retained run");
        support.sendCommandHelp(
                sender,
                "/walk admin run player <player-uuid> [limit]",
                "Filter by immutable player UUID");
        support.sendCommandHelp(
                sender,
                "/walk admin run arena <arena-id> [limit]",
                "Filter by exact arena ID");
        support.sendCommandHelp(
                sender,
                "/walk admin run season <season-uuid> [limit]",
                "Filter by exact season UUID");
        support.sendLine(
                sender,
                "&7Limits are 1-100 (default 20). Names are never ownership filters.");
    }

    void list(
            CommandSender sender,
            Optional<RunStatus> status,
            int limit) {
        if (!authorized(sender)) {
            return;
        }
        RunInvestigationQuery query = status
                .map(value -> RunInvestigationQuery.forStatus(value, limit))
                .orElseGet(() -> RunInvestigationQuery.all(limit));
        String filter = status
                .map(value -> "status:" + value)
                .orElse("all");
        list(sender, query, filter);
    }

    void inspect(CommandSender sender, UUID runId) {
        if (!authorized(sender)) {
            return;
        }
        support.completeOnMainThread(
                support.scores.run(runId),
                retained -> {
                    audit(
                            sender,
                            "run:" + runId,
                            retained.isPresent() ? 1 : 0);
                    if (retained.isEmpty()) {
                        support.sendLine(
                                sender, "&eNo retained run has that UUID.");
                        return;
                    }
                    show(sender, retained.orElseThrow());
                },
                "retained-run inspection",
                sender);
    }

    void player(CommandSender sender, UUID playerId, int limit) {
        if (authorized(sender)) {
            list(
                    sender,
                    RunInvestigationQuery.forPlayer(playerId, limit),
                    "player:" + playerId);
        }
    }

    void arena(CommandSender sender, String arenaId, int limit) {
        if (!authorized(sender)) {
            return;
        }
        try {
            list(
                    sender,
                    RunInvestigationQuery.forArena(arenaId, limit),
                    "arena:" + CommandSupport.safeEvidenceText(arenaId, 128));
        } catch (IllegalArgumentException invalidArena) {
            support.sendLine(
                    sender,
                    "&cArena IDs must be nonblank, contain no control characters, "
                            + "and use at most 128 characters.");
        }
    }

    void season(CommandSender sender, UUID seasonId, int limit) {
        if (authorized(sender)) {
            list(
                    sender,
                    RunInvestigationQuery.forSeason(seasonId, limit),
                    "season:" + seasonId);
        }
    }

    private boolean authorized(CommandSender sender) {
        return support.requireAdministrativePermission(
                sender, support.permissions().adminInvestigate());
    }

    private void list(
            CommandSender sender,
            RunInvestigationQuery query,
            String filter) {
        support.completeOnMainThread(
                support.scores.investigateRuns(query),
                runs -> {
                    audit(sender, filter, runs.size());
                    support.sendHeader(sender, "Retained runs");
                    support.sendField(
                            sender,
                            "Filter",
                            CommandSupport.safeEvidenceText(filter, 160));
                    support.sendField(
                            sender,
                            "Results",
                            Integer.toString(runs.size()));
                    if (runs.isEmpty()) {
                        support.sendLine(
                                sender,
                                "&7No retained runs match that exact filter.");
                        return;
                    }
                    for (RunInvestigationRecord run : runs) {
                        String plan = run.rewardPlanId()
                                .map(id -> id + "/" + run.rewardPlanStatus().orElseThrow())
                                .orElse("none");
                        support.sendLine(
                                sender,
                                "&3{{runId}} &7| &f{{status}} &7| player &f{{playerId}} "
                                        + "&7| arena &f{{arenaId}} &7| started &f{{startedAt}} "
                                        + "&7| score &f{{score}} &7| plan &f{{plan}}",
                                Map.of(
                                        "runId", run.runId(),
                                        "status", run.status(),
                                        "playerId", run.playerId(),
                                        "arenaId",
                                        CommandSupport.safeEvidenceText(
                                                run.arenaId(), 128),
                                        "startedAt",
                                        CommandSupport.SNAPSHOT_TIME.format(
                                                run.startedAt()),
                                        "score",
                                        run.score()
                                                .map(String::valueOf)
                                                .orElse("-"),
                                        "plan", plan));
                    }
                },
                "retained-run query",
                sender);
    }

    private void show(
            CommandSender sender,
            RunInvestigationRecord run) {
        support.sendHeader(sender, "Retained run " + run.runId());
        support.sendField(sender, "Player UUID", run.playerId().toString());
        support.sendField(sender, "Status", run.status().name());
        support.sendField(
                sender,
                "Arena",
                CommandSupport.safeEvidenceText(run.arenaId(), 128));
        support.sendField(
                sender,
                "Started",
                CommandSupport.SNAPSHOT_TIME.format(run.startedAt()));
        support.sendField(
                sender,
                "Ended",
                run.endedAt()
                        .map(CommandSupport.SNAPSHOT_TIME::format)
                        .orElse("not recorded"));
        support.sendField(
                sender,
                "Score",
                run.score().map(String::valueOf).orElse("not projected"));
        support.sendField(
                sender,
                "End reason",
                run.endReason()
                        .map(value -> CommandSupport.safeEvidenceText(value, 64))
                        .orElse("not recorded"));
        support.sendField(
                sender,
                "Release",
                CommandSupport.safeEvidenceText(run.release(), 128));
        support.sendField(
                sender,
                "Season UUID",
                run.seasonId().map(UUID::toString).orElse("none"));
        support.sendField(
                sender,
                "Reward plan",
                run.rewardPlanId().map(UUID::toString).orElse("none"));
        support.sendField(
                sender,
                "Reward status",
                run.rewardPlanStatus()
                        .map(RewardPlanStatus::name)
                        .orElse("none"));
    }

    private void audit(CommandSender sender, String filter, int results) {
        support.operations.audit(
                "run.investigation",
                CommandSupport.operatorId(sender),
                null,
                Map.of(
                        "filter",
                        CommandSupport.safeEvidenceText(filter, 160),
                        "results", results));
    }
}
