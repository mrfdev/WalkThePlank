package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.database.RewardPlanRecord;
import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RewardStepRecord;
import com.mrfdev.walktheplank.database.RewardStepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.CommandSender;

/** Durable reward evidence commands. This module never dispatches or replays reward commands. */
final class RewardCommands {
    private final CommandSupport support;

    RewardCommands(CommandSupport support) {
        this.support = support;
    }

    void help(CommandSender sender) {
        if (!authorized(sender)) {
            return;
        }
        support.sendHeader(sender, "Durable rewards");
        support.sendCommandHelp(
                sender,
                "/walk admin reward list [status] [limit]",
                "List redacted plans, newest first");
        support.sendCommandHelp(
                sender,
                "/walk admin reward inspect <plan-uuid>",
                "Show roots, hashes, and persisted outcomes only");
        support.sendCommandHelp(
                sender,
                "/walk admin reward resolve <plan-uuid> <step> "
                        + "<succeeded|failed|skipped> confirm",
                "Resolve UNKNOWN evidence without replaying a command");
        support.sendCommandHelp(
                sender,
                "/walk admin reward abandon <plan-uuid> confirm",
                "Freeze every remaining PENDING step without replay");
    }

    void list(
            CommandSender sender,
            RewardPlanStatus status,
            int limit) {
        if (!authorized(sender)) {
            return;
        }
        support.completeOnMainThread(
                support.scores.recentRewardPlans(status, limit),
                plans -> {
                    support.sendHeader(sender, "Reward plans: " + status);
                    if (plans.isEmpty()) {
                        support.sendLine(sender, "&7No plans have this status.");
                        return;
                    }
                    for (RewardPlanRecord plan : plans) {
                        support.sendLine(
                                sender,
                                "&3{{planId}} &7- &f{{status}} &7| run &f{{runId}} "
                                        + "&7| steps &f{{steps}}",
                                Map.of(
                                        "planId", plan.planId(),
                                        "status", plan.status(),
                                        "runId", plan.runId(),
                                        "steps", plan.steps().size()));
                    }
                },
                "reward-plan list",
                sender);
    }

    void inspect(CommandSender sender, UUID planId) {
        if (!authorized(sender)) {
            return;
        }
        support.completeOnMainThread(
                support.scores.rewardPlan(planId),
                plan -> {
                    if (plan.isEmpty()) {
                        support.sendLine(
                                sender,
                                "&eNo retained reward plan has that ID.");
                        return;
                    }
                    showPlan(sender, plan.orElseThrow());
                },
                "reward-plan inspection",
                sender);
    }

    void resolve(
            CommandSender sender,
            UUID planId,
            int stepIndex,
            RewardStepStatus resolution) {
        if (!authorized(sender)) {
            return;
        }
        if (!List.of(
                        RewardStepStatus.SUCCEEDED,
                        RewardStepStatus.FAILED,
                        RewardStepStatus.SKIPPED)
                .contains(resolution)) {
            support.sendLine(
                    sender,
                    "&cResolution must be succeeded, failed, or skipped.");
            return;
        }
        support.completeOnMainThread(
                support.scores.resolveUnknownRewardStep(
                        planId, stepIndex, resolution, Instant.now()),
                plan -> {
                    support.games.auditRewardResolution(
                            CommandSupport.operatorId(sender),
                            planId,
                            stepIndex,
                            resolution);
                    support.sendLine(
                            sender,
                            "&aResolved UNKNOWN reward step &f{{step}} &aas &f{{resolution}}"
                                    + "&a; plan is now &f{{status}}&a.",
                            Map.of(
                                    "step", stepIndex,
                                    "resolution", resolution,
                                    "status", plan.status()));
                },
                "UNKNOWN reward resolution",
                sender);
    }

    void abandon(CommandSender sender, UUID planId) {
        if (!authorized(sender)) {
            return;
        }
        support.completeOnMainThread(
                support.scores.finalizeRewardPlan(planId, Instant.now()),
                plan -> {
                    support.games.auditRewardAbandon(
                            CommandSupport.operatorId(sender),
                            planId,
                            plan.status());
                    support.sendLine(
                            sender,
                            "&aReward plan &f{{planId}} &ais now &f{{status}}"
                                    + "&a. No command was replayed.",
                            Map.of(
                                    "planId", planId,
                                    "status", plan.status()));
                },
                "reward-plan abandonment",
                sender);
    }

    private boolean authorized(CommandSender sender) {
        return support.requireAdministrativePermission(
                sender, support.permissions().adminReward());
    }

    private void showPlan(CommandSender sender, RewardPlanRecord plan) {
        support.sendHeader(sender, "Reward plan " + plan.planId());
        support.sendField(sender, "Run", plan.runId().toString());
        support.sendField(sender, "Status", plan.status().name());
        support.sendField(
                sender,
                "Created",
                CommandSupport.SNAPSHOT_TIME.format(plan.createdAt()));
        for (RewardStepRecord step : plan.steps()) {
            support.sendLine(
                    sender,
                    "&7Step &f{{index}} &7| root &f{{root}} &7| hash &f{{hash}}... "
                            + "&7| &f{{status}}",
                    Map.of(
                            "index", step.index(),
                            "root", step.commandRoot(),
                            "hash", step.commandHash().substring(0, 12),
                            "status", step.status()));
        }
    }
}
