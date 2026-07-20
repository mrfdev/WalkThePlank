package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.build.PaperRuntimePolicy;
import com.mrfdev.walktheplank.config.PermissionSettings;
import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.DatabaseDoctorReport;
import com.mrfdev.walktheplank.database.DurabilityMetrics;
import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RewardStepStatus;
import com.mrfdev.walktheplank.database.ScoreSnapshot;
import com.mrfdev.walktheplank.game.GameManager;
import com.mrfdev.walktheplank.game.GameManager.QueueStatus;
import com.mrfdev.walktheplank.gui.MenuService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** SQLite health, privacy-safe doctor reports, and operational diagnostics. */
final class DatabaseCommands {
    private final CommandSupport support;
    private final AtomicBoolean doctorProbePending = new AtomicBoolean();

    DatabaseCommands(CommandSupport support) {
        this.support = support;
    }

    void doctor(CommandSender sender) {
        if (!authorized(sender)) {
            return;
        }
        if (!doctorProbePending.compareAndSet(false, true)) {
            support.sendLine(
                    sender,
                    "&eA WalkThePlank doctor probe is already running.");
            return;
        }
        support.sendLine(
                sender,
                "&7Running a read-only SQLite check asynchronously; "
                        + "the privacy-safe report will follow.");
        support.operations.audit(
                "admin.doctor",
                CommandSupport.operatorId(sender),
                null,
                Map.of(
                        "actor", CommandSupport.operatorKind(sender),
                        "result", "started"));

        CompletableFuture<DatabaseDoctorReport> probe;
        try {
            probe = support.scores.inspectDatabase();
        } catch (RuntimeException | LinkageError startFailure) {
            doctorProbePending.set(false);
            reportDoctorFailure(sender, startFailure);
            return;
        }
        probe.whenComplete((database, failure) -> {
            doctorProbePending.set(false);
            if (failure != null) {
                reportDoctorFailure(sender, failure);
                return;
            }
            support.scheduleCommandReply(sender, () -> {
                try {
                    int warnings = showDoctorReport(sender, database);
                    support.operations.audit(
                            "admin.doctor",
                            CommandSupport.operatorId(sender),
                            null,
                            Map.of(
                                    "actor",
                                    CommandSupport.operatorKind(sender),
                                    "result",
                                    warnings == 0 ? "pass" : "warn",
                                    "warnings", warnings,
                                    "sqlite_quick_check",
                                    database.quickCheckPassed()));
                } catch (RuntimeException | LinkageError reportFailure) {
                    support.plugin.getLogger().log(
                            Level.WARNING,
                            "Could not render the privacy-safe "
                                    + "WalkThePlank doctor report",
                            reportFailure);
                    support.sendLine(
                            sender,
                            "&cThe doctor report could not be rendered safely; "
                                    + "review the server log.");
                }
            });
        });
    }

    void debug(CommandSender sender, String requestedPage) {
        if (!authorized(sender)) {
            return;
        }
        String page = requestedPage.toLowerCase(java.util.Locale.ROOT);
        if (!CommandSupport.DEBUG_PAGES.contains(page)) {
            support.sendLine(
                    sender,
                    "&cUnknown debug page. Use: {{pages}}",
                    Map.of(
                            "pages",
                            String.join(", ", CommandSupport.DEBUG_PAGES)));
            return;
        }
        if (page.equals("all")) {
            for (String debugPage : CommandSupport.DEBUG_PAGES) {
                if (!debugPage.equals("all")) {
                    showDebugPage(sender, debugPage);
                }
            }
            return;
        }
        showDebugPage(sender, page);
    }

    void health(CommandSender sender) {
        if (authorized(sender)) {
            showHealth(sender);
        }
    }

    private boolean authorized(CommandSender sender) {
        return support.requireAdministrativePermission(
                sender, support.permissions().adminDebug());
    }

    private void reportDoctorFailure(
            CommandSender sender,
            Throwable failure) {
        support.plugin.getLogger().log(
                Level.WARNING,
                "Could not complete the read-only WalkThePlank SQLite doctor probe",
                failure);
        support.scheduleCommandReply(sender, () -> {
            support.operations.audit(
                    "admin.doctor",
                    CommandSupport.operatorId(sender),
                    null,
                    Map.of(
                            "actor", CommandSupport.operatorKind(sender),
                            "result", "sqlite_probe_failed"));
            support.sendField(
                    sender, "Doctor result", "SQLITE_PROBE_FAILED");
            support.sendLine(
                    sender,
                    "&cThe read-only SQLite doctor probe failed. "
                            + "No path, SQL text, or database content is included here; "
                            + "review the protected server log.");
        });
    }

    private int showDoctorReport(
            CommandSender sender,
            DatabaseDoctorReport database) {
        RuntimeSettings current = support.settings.get();
        ScoreSnapshot scoreSnapshot = support.scores.snapshot();
        DurabilityMetrics durability = support.scores.durabilityMetrics();
        var runtimeMetrics = support.games.operationalMetrics();
        var operationsIo = support.operations.ioStatus();
        var auditHealth = support.operations.auditHealth();
        var recoveryDurability = support.games.recoveryDurabilityStatus();
        QueueStatus queue = support.games.queueStatus();
        GameManager.TaskHealth gameTasks = support.games.taskHealth();
        MenuService.Health menuHealth = support.menus.health();
        var recovery = support.games.playerRecoveryHealth();

        List<BukkitTask> pluginTasks = support.plugin.getServer()
                .getScheduler()
                .getPendingTasks()
                .stream()
                .filter(task -> task.getOwner().equals(support.plugin))
                .toList();
        long synchronousTasks =
                pluginTasks.stream().filter(BukkitTask::isSync).count();
        long asynchronousTasks = pluginTasks.size() - synchronousTasks;

        int runtimeJava = Runtime.version().feature();
        boolean javaMatches = Integer.toString(runtimeJava)
                .equals(support.buildInfo.javaTarget());
        PaperRuntimePolicy.Verification paperRuntime =
                PaperRuntimePolicy.current(support.buildInfo);
        boolean paperMatches = paperRuntime.supported();

        List<String> commandRoots =
                current.allowedRewardCommandRoots().stream().sorted().toList();
        int missingCommandRoots = 0;
        for (String root : commandRoots) {
            if (support.plugin.getServer()
                    .getCommandMap()
                    .getCommand(root) == null) {
                missingCommandRoots++;
            }
        }

        long unknownPlans = durability.rewardPlansByStatus()
                .getOrDefault(RewardPlanStatus.UNKNOWN, 0L);
        long pendingPlans = durability.rewardPlansByStatus()
                .getOrDefault(RewardPlanStatus.PENDING, 0L);
        long inProgressPlans = durability.rewardPlansByStatus()
                .getOrDefault(RewardPlanStatus.IN_PROGRESS, 0L);
        long unknownSteps = durability.rewardStepsByStatus()
                .getOrDefault(RewardStepStatus.UNKNOWN, 0L);
        long dispatchingSteps = durability.rewardStepsByStatus()
                .getOrDefault(RewardStepStatus.DISPATCHING, 0L);

        int warnings = 0;
        warnings += support.buildInfo.sourceDirty() ? 1 : 0;
        warnings += javaMatches ? 0 : 1;
        warnings += paperMatches ? 0 : 1;
        warnings += database.quickCheckPassed() ? 0 : 1;
        warnings += current.finishCommandsEnabled()
                        && missingCommandRoots > 0
                ? 1
                : 0;
        warnings += support.games.quarantinedArenas() > 0
                        || support.games.conflictedRestorations() > 0
                ? 1
                : 0;
        warnings += recovery.healthy() ? 0 : 1;
        warnings += unknownPlans > 0L
                        || unknownSteps > 0L
                        || dispatchingSteps > 0L
                ? 1
                : 0;
        warnings += durability.lastDatabaseFailure().isPresent() ? 1 : 0;
        warnings += runtimeMetrics.degraded() ? 1 : 0;
        warnings += auditHealth.verifiedAtStartup() && auditHealth.writerHealthy()
                ? 0
                : 1;
        warnings += operationsIo.failed() > 0L
                        || operationsIo.rejected() > 0L
                        || operationsIo.closing()
                ? 1
                : 0;
        warnings += recoveryDurability.failed() > 0L
                        || recoveryDurability.rejected() > 0L
                        || recoveryDurability.closing()
                ? 1
                : 0;

        support.sendHeader(sender, "Doctor support report");
        support.sendField(
                sender, "Release", support.buildInfo.releaseLabel());
        support.sendField(
                sender, "Artifact", support.buildInfo.artifactFile());
        support.sendField(
                sender,
                "Source commit",
                support.buildInfo.sourceCommit());
        support.sendField(
                sender,
                "Source state",
                support.buildInfo.sourceDirty() ? "DIRTY" : "clean");
        support.sendField(
                sender,
                "Java target/runtime",
                support.buildInfo.javaTarget() + " / "
                        + System.getProperty("java.version", "unknown")
                        + (javaMatches ? " (match)" : " (MISMATCH)"));
        support.sendField(
                sender,
                "Paper target/runtime",
                paperRuntime.targetLabel() + " / "
                        + paperRuntime.runtimeLabel()
                        + (paperMatches ? " (match)" : " (MISMATCH)"));

        support.sendHeader(sender, "Doctor: hooks and command roots");
        sendDoctorHook(sender, "PlaceholderAPI", true);
        for (String pluginName : List.of(
                "CMI",
                "CMILib",
                "Vault",
                "UltimateFireworks",
                "PyroWelcomesPro",
                "PyroLib")) {
            sendDoctorHook(sender, pluginName, false);
        }
        support.sendField(
                sender,
                "Reward commands",
                current.finishCommandsEnabled() ? "enabled" : "disabled");
        support.sendField(
                sender,
                "Configured roots",
                commandRoots.size() + " total / "
                        + missingCommandRoots + " missing");
        for (String root : commandRoots) {
            boolean available = support.plugin.getServer()
                    .getCommandMap()
                    .getCommand(root) != null;
            support.sendField(
                    sender,
                    "Root " + root,
                    available ? "available" : "missing");
        }

        support.sendHeader(sender, "Doctor: SQLite and durability");
        support.sendField(
                sender,
                "SQLite quick_check",
                database.quickCheckPassed() ? "ok" : "FAILED");
        support.sendField(
                sender,
                "Database size",
                database.databaseBytes() + " bytes");
        support.sendField(
                sender, "WAL size", database.walBytes() + " bytes");
        support.sendField(
                sender,
                "Probe latency",
                database.latencyMillis() + " ms");
        support.sendField(
                sender,
                "Migration backups",
                database.migrationBackupCount() + " retained / "
                        + database.migrationBackupRetention() + " configured / "
                        + database.migrationBackupsPrunedAtStartup() + " pruned at startup");
        support.sendField(
                sender,
                "Score rows",
                Integer.toString(scoreSnapshot.totalEntries()));
        support.sendField(
                sender,
                "Pending database writes",
                Integer.toString(durability.pendingMutations()));
        support.sendField(
                sender,
                "Restoration journal",
                support.games.pendingRestorations() + " pending / "
                        + support.games.conflictedRestorations() + " conflicted / "
                        + support.games.quarantinedArenas() + " quarantined arenas");
        support.sendField(
                sender,
                "Player recovery journal",
                recovery.pendingRecords() + " pending / "
                        + recovery.invalidRecords() + " invalid / "
                        + (recovery.healthy() ? "healthy" : "degraded"));
        support.sendField(
                sender,
                "Uncertain rewards",
                unknownPlans + " UNKNOWN plans / "
                        + unknownSteps + " UNKNOWN steps / "
                        + dispatchingSteps + " DISPATCHING steps");
        support.sendField(
                sender,
                "Unfinished rewards",
                pendingPlans + " PENDING plans / "
                        + inProgressPlans + " IN_PROGRESS plans");
        durability.lastDatabaseFailure().ifPresent(lastFailure ->
                support.sendField(
                        sender,
                        "Last database failure",
                        lastFailure.category() + "/"
                                + lastFailure.exceptionType()));

        support.sendHeader(sender, "Doctor: queue and tasks");
        support.sendField(
                sender,
                "Queue",
                (queue.enabled() ? "enabled" : "disabled") + " / "
                        + (queue.paused() ? "paused" : "running") + " / "
                        + queue.total() + " total / "
                        + queue.waiting() + " waiting / "
                        + queue.ready() + " ready");
        support.sendField(
                sender,
                "Gameplay",
                support.games.activeSessions() + " active / "
                        + gameTasks.pendingStarts() + " pending starts / "
                        + gameTasks.pendingStartAbandonments() + " start cleanups / "
                        + gameTasks.pendingExternalTeleportChecks() + " teleport checks / "
                        + gameTasks.pendingPlayerRecoveryLookups() + " recovery lookups / "
                        + gameTasks.pendingPlayerRecoveryCompletions()
                        + " recovery completions");
        support.sendField(
                sender,
                "GUI",
                menuHealth.openSessions() + " sessions / "
                        + menuHealth.pendingActions() + " pending actions");
        support.sendField(
                sender,
                "Paper tasks",
                pluginTasks.size() + " owned / "
                        + synchronousTasks + " sync / "
                        + asynchronousTasks + " async");
        support.sendField(
                sender,
                "Operations I/O",
                operationsIo.queued() + "/" + operationsIo.queueCapacity()
                        + " queued / " + operationsIo.active() + " active / "
                        + operationsIo.requiredAccepted() + " required accepted / "
                        + operationsIo.bestEffortAccepted()
                        + " best-effort accepted / "
                        + operationsIo.completed() + " completed / "
                        + operationsIo.failed() + " failed / "
                        + operationsIo.rejected() + " rejected / "
                        + workerState(
                                operationsIo.terminated(),
                                operationsIo.closing()));
        support.sendField(
                sender,
                "Audit chain",
                (auditHealth.verifiedAtStartup() ? "verified" : "unverified")
                        + " / "
                        + (auditHealth.writerHealthy() ? "healthy" : "FAILED")
                        + " / sequence " + auditHealth.sequence()
                        + " / anchor " + auditHealth.anchorSequence());
        support.sendField(
                sender,
                "Recovery durability",
                recoveryDurability.queued() + "/"
                        + recoveryDurability.queueCapacity() + " queued / "
                        + recoveryDurability.active() + " active / "
                        + recoveryDurability.accepted() + " accepted / "
                        + recoveryDurability.completed() + " completed / "
                        + recoveryDurability.failed() + " failed / "
                        + recoveryDurability.rejected() + " rejected / "
                        + workerState(
                                recoveryDurability.terminated(),
                                recoveryDurability.closing()));
        support.sendField(
                sender,
                "Runtime failures",
                runtimeMetrics.restorationFailures() + " restoration / "
                        + runtimeMetrics.rewardFailures() + " reward / "
                        + runtimeMetrics.repositoryFailures() + " repository / "
                        + runtimeMetrics.auditFailures() + " audit");
        if (runtimeMetrics.lastFailure() != null) {
            support.sendField(
                    sender,
                    "Last runtime failure",
                    runtimeMetrics.lastFailure().subsystem() + "/"
                            + runtimeMetrics.lastFailure().summary());
        }
        support.sendField(
                sender,
                "Result",
                warnings == 0 ? "PASS" : "WARN (" + warnings + ")");
        support.sendLine(
                sender,
                "&7Privacy: no player/season names, coordinates, filesystem paths, "
                        + "raw reward command lines, credentials, SQL text, or "
                        + "exception messages are included.");
        return warnings;
    }

    private static String workerState(boolean terminated, boolean closing) {
        return terminated ? "terminated" : closing ? "closing" : "open";
    }

    private void showDebugPage(CommandSender sender, String page) {
        switch (page) {
            case "overview" -> showOverview(sender);
            case "health" -> showHealth(sender);
            case "hooks" -> showHooks(sender);
            case "commands" -> showCommands(sender);
            case "permissions" -> showPermissions(sender);
            case "placeholders" -> showPlaceholders(sender);
            case "config" -> showConfig(sender);
            default -> throw new IllegalArgumentException(
                    "Unknown debug page " + page);
        }
    }

    private void showOverview(CommandSender sender) {
        PaperRuntimePolicy.Verification paperRuntime =
                PaperRuntimePolicy.current(support.buildInfo);
        support.sendHeader(sender, "Diagnostics: overview");
        support.sendField(
                sender, "Release", support.buildInfo.releaseLabel());
        support.sendField(
                sender, "Artifact", support.buildInfo.artifactFile());
        support.sendField(
                sender, "Source", support.buildInfo.sourceLabel());
        support.sendField(
                sender,
                "Runtime",
                "Java " + System.getProperty("java.version") + " / "
                        + paperRuntime.runtimeLabel());
        support.sendField(
                sender,
                "Compile targets",
                "Java " + support.buildInfo.javaTarget() + ", "
                        + paperRuntime.targetLabel()
                        + " (API " + support.buildInfo.paperApiVersion() + ")"
                        + ", PlaceholderAPI "
                        + support.buildInfo.placeholderApiVersion());
    }

    private void showHealth(CommandSender sender) {
        ScoreSnapshot snapshot = support.scores.snapshot();
        DurabilityMetrics durability = support.scores.durabilityMetrics();
        var runtime = support.games.operationalMetrics();
        QueueStatus queue =
                support.games.queueStatus(new UUID(0L, 0L));
        support.sendHeader(sender, "Diagnostics: health");
        support.sendField(
                sender,
                "Arenas",
                support.games.activeSessions() + " active / "
                        + support.games.availableArenas() + " free / "
                        + support.games.totalArenas() + " configured");
        support.sendField(
                sender,
                "Restoration",
                support.games.quarantinedArenas() + " quarantined / "
                        + support.games.pendingRestorations() + " pending / "
                        + support.games.conflictedRestorations() + " conflicted");
        var playerRecovery = support.games.playerRecoveryHealth();
        support.sendField(
                sender,
                "Player recovery",
                playerRecovery.pendingRecords() + " pending / "
                        + playerRecovery.invalidRecords() + " invalid / "
                        + (playerRecovery.healthy() ? "healthy" : "degraded"));
        support.sendField(
                sender,
                "Database",
                "SQLite, " + snapshot.totalEntries() + " score rows");
        support.sendField(
                sender,
                "Snapshot",
                CommandSupport.SNAPSHOT_TIME.format(snapshot.capturedAt()));
        support.sendField(
                sender,
                "Writes",
                durability.pendingMutations() + " pending; last success "
                        + durability.lastSuccessfulWriteAt()
                                .map(CommandSupport.SNAPSHOT_TIME::format)
                                .orElse("none"));
        support.sendField(
                sender,
                "Runs",
                durability.retainedRuns() + " retained; "
                        + runtime.sessionsStarted() + " started / "
                        + runtime.sessionsEnded() + " ended this uptime");
        support.sendField(
                sender,
                "Queue",
                queue.total() + " player(s), "
                        + (queue.paused() ? "paused" : "running"));
        support.sendField(
                sender,
                "Season",
                durability.activeSeason()
                        .map(season -> season.name() + " ("
                                + season.id() + ")")
                        .orElse("none active"));
        support.sendField(
                sender,
                "Reward plans",
                support.settings.get().finishCommandsEnabled()
                        ? durability.rewardPlansByStatus().toString()
                        : "disabled");
        durability.lastDatabaseFailure().ifPresent(failure ->
                support.sendField(
                        sender,
                        "Last database failure",
                        failure.category() + "/" + failure.exceptionType()
                                + " at "
                                + CommandSupport.SNAPSHOT_TIME.format(
                                        failure.occurredAt())));
        if (runtime.lastFailure() != null) {
            support.sendField(
                    sender,
                    "Last runtime failure",
                    runtime.lastFailure().subsystem() + "/"
                            + runtime.lastFailure().summary() + " at "
                            + CommandSupport.SNAPSHOT_TIME.format(
                                    runtime.lastFailure().occurredAt()));
        }
    }

    private void showHooks(CommandSender sender) {
        support.sendHeader(sender, "Diagnostics: hooks");
        sendPlaceholderHook(sender);
        for (String name : List.of(
                "CMI",
                "CMILib",
                "Vault",
                "UltimateFireworks",
                "PyroWelcomesPro",
                "PyroLib")) {
            sendPluginPresence(sender, name);
        }
    }

    private void showCommands(CommandSender sender) {
        support.sendHeader(sender, "Diagnostics: commands");
        support.sendLine(
                sender,
                "&7Player: &f/walk, play, queue, leave, stats, top, settings, info, help");
        support.sendLine(
                sender,
                "&7Staff: &f/walk admin open, reload, stop, recover, validate, "
                        + "arena, queue, season, export, reward, run, status, "
                        + "debug, doctor");
        support.sendLine(
                sender,
                "&7Registration: &fPaper lifecycle Brigadier tree");
        support.sendLine(
                sender,
                "&7Compatibility: &f/walk open, reload, version; "
                        + "aliases /infp and /infinityparkour");
    }

    private void showPermissions(CommandSender sender) {
        support.sendHeader(sender, "Diagnostics: permissions");
        PermissionSettings permissions = support.permissions();
        for (Map.Entry<String, String> entry : Map.ofEntries(
                Map.entry("open", permissions.openGui()),
                Map.entry("play", permissions.playGame()),
                Map.entry("leave", permissions.leaveArena()),
                Map.entry("stats", permissions.stats()),
                Map.entry("top", permissions.top()),
                Map.entry("info", permissions.info()),
                Map.entry("help", permissions.help()),
                Map.entry("preferences", permissions.preferences()),
                Map.entry("admin", permissions.admin()),
                Map.entry("reload", permissions.reload()),
                Map.entry("admin.open", permissions.adminOpen()),
                Map.entry("admin.debug", permissions.adminDebug()),
                Map.entry("admin.stop", permissions.adminStop()),
                Map.entry("admin.recover", permissions.adminRecover()),
                Map.entry("admin.validate", permissions.adminValidate()),
                Map.entry("admin.arena", permissions.adminArena()),
                Map.entry("admin.queue", permissions.adminQueue()),
                Map.entry("admin.season", permissions.adminSeason()),
                Map.entry("admin.export", permissions.adminExport()),
                Map.entry("admin.reward", permissions.adminReward()),
                Map.entry(
                        "admin.investigate",
                        permissions.adminInvestigate())).entrySet()) {
            boolean granted = sender.hasPermission(entry.getValue());
            support.sendLine(
                    sender,
                    "&7{{permissionKey}}: &f{{permissionName}} "
                            + (granted ? "&a" : "&c") + "{{state}}",
                    Map.of(
                            "permissionKey", entry.getKey(),
                            "permissionName", entry.getValue(),
                            "state", granted ? "YES" : "NO"));
        }
    }

    private void showPlaceholders(CommandSender sender) {
        support.sendHeader(sender, "Diagnostics: placeholders");
        support.sendField(
                sender,
                "Registration",
                support.placeholderRegistered.getAsBoolean()
                        ? "active"
                        : "inactive");
        support.sendLine(sender, "&f%infinityparkour_version%");
        support.sendLine(
                sender,
                "&f%infinityparkour_score% &7| rank | percentile | "
                        + "current_score | in_game");
        support.sendLine(sender, "&f%infinityparkour_total_players%");
        support.sendLine(
                sender,
                "&f%infinityparkour_active_arenas% &7| available_arenas | "
                        + "total_arenas | quarantined_arenas");
        support.sendLine(
                sender,
                "&f%infinityparkour_queue_total% &7| queue_waiting | "
                        + "queue_ready_count | queue_enabled | queue_paused");
        support.sendLine(
                sender,
                "&f%infinityparkour_queue_position% &7| queue_ready");
        support.sendLine(
                sender,
                "&f%infinityparkour_current_arena% &7| elapsed_seconds | "
                        + "idle_seconds");
        support.sendLine(
                sender,
                "&f%infinityparkour_previous_best% &7| best_delta");
        support.sendLine(
                sender,
                "&f%infinityparkour_top_<1-10>_<name|score|rank>%");
        support.sendLine(
                sender,
                "&f%infinityparkour_active_season_id% &7| "
                        + "active_season_name | season_total_players");
        support.sendLine(
                sender,
                "&f%infinityparkour_season_score% &7| season_rank | "
                        + "season_percentile");
        support.sendLine(
                sender,
                "&f%infinityparkour_season_top_<1-10>_<name|score|rank>%");
    }

    private void showConfig(CommandSender sender) {
        RuntimeSettings current = support.settings.get();
        support.sendHeader(sender, "Diagnostics: safe config");
        support.sendField(
                sender,
                "Arenas",
                Integer.toString(current.arenas().size()));
        support.sendField(
                sender, "Blocks", current.parkourBlocks().toString());
        support.sendField(
                sender,
                "Bounds",
                "radius " + current.horizontalRadius()
                        + ", fall distance " + current.fallDistance());
        support.sendField(
                sender,
                "Timeouts",
                current.maximumRunSeconds() + "s maximum / "
                        + current.idleTimeoutSeconds() + "s idle");
        support.sendField(
                sender,
                "Generation",
                current.onlyReplaceAir()
                        ? "air only"
                        : "replace allowed");
        support.sendField(
                sender,
                "Particles",
                current.particlesEnabled()
                        ? current.particle() + " x"
                                + current.particleCount()
                        : "disabled");
        support.sendField(
                sender,
                "Rewards",
                current.finishCommandsEnabled()
                        ? current.rewardTiers().size()
                                + " configured tier(s), personal-best-only="
                                + current.rewardsOnlyOnPersonalBest()
                        : "disabled");
    }

    private void sendDoctorHook(
            CommandSender sender,
            String pluginName,
            boolean placeholderApi) {
        Plugin dependency = support.plugin.getServer()
                .getPluginManager()
                .getPlugin(pluginName);
        if (dependency == null) {
            support.sendField(sender, pluginName, "not installed");
            return;
        }
        String state = dependency.isEnabled() ? "enabled" : "disabled";
        if (placeholderApi) {
            state += support.placeholderRegistered.getAsBoolean()
                    ? ", expansion active"
                    : ", expansion inactive";
        } else {
            state += ", presence only";
        }
        support.sendField(
                sender,
                pluginName,
                dependency.getPluginMeta().getVersion()
                        + " (" + state + ")");
    }

    private void sendPlaceholderHook(CommandSender sender) {
        Plugin dependency = support.plugin.getServer()
                .getPluginManager()
                .getPlugin("PlaceholderAPI");
        if (dependency == null) {
            support.sendField(sender, "PlaceholderAPI", "not installed");
            return;
        }
        String state = dependency.isEnabled() ? "enabled" : "disabled";
        state += support.placeholderRegistered.getAsBoolean()
                ? ", expansion active"
                : ", expansion inactive";
        support.sendField(
                sender,
                "PlaceholderAPI",
                dependency.getPluginMeta().getVersion()
                        + " (" + state + ")");
    }

    private void sendPluginPresence(
            CommandSender sender,
            String pluginName) {
        Plugin dependency = support.plugin.getServer()
                .getPluginManager()
                .getPlugin(pluginName);
        if (dependency == null) {
            support.sendField(sender, pluginName, "not installed");
            return;
        }
        String state = dependency.isEnabled() ? "enabled" : "disabled";
        support.sendField(
                sender,
                pluginName,
                dependency.getPluginMeta().getVersion() + " ("
                        + state + ", presence only)");
    }
}
