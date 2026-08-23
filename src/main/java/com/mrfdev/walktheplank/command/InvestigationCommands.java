package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RunInvestigationQuery;
import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import com.mrfdev.walktheplank.database.RunStatus;
import com.mrfdev.walktheplank.export.RunInvestigationExportCommitUncertainException;
import com.mrfdev.walktheplank.export.RunInvestigationExportService;
import com.mrfdev.walktheplank.security.ActionRateLimiter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;

/** Privacy-safe retained-run investigation by immutable identifiers. */
final class InvestigationCommands {
    private static final UUID SYSTEM_RATE_LIMIT_KEY = new UUID(0L, 0L);
    private static final Duration QUERY_COOLDOWN = Duration.ofSeconds(1L);
    private static final Duration EXPORT_COOLDOWN = Duration.ofSeconds(10L);

    private final CommandSupport support;
    private final RunInvestigationExportService exports;
    private final ActionRateLimiter rateLimiter = new ActionRateLimiter();

    InvestigationCommands(CommandSupport support) {
        this.support = support;
        exports = new RunInvestigationExportService(
                support.plugin.getDataFolder().toPath());
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
        support.sendCommandHelp(
                sender,
                "/walk admin run release <exact-release> [limit]",
                "Filter by an exact quoted release identity");
        support.sendCommandHelp(
                sender,
                "/walk admin run started <from-inclusive> <before-exclusive> [limit]",
                "Filter a half-open RFC 3339 start-time window");
        if (support.hasAdministrativePermission(
                sender, support.permissions().adminExport())) {
            support.sendCommandHelp(
                    sender,
                    "/walk admin run export <filter> ...",
                    "Write one atomic privacy-safe JSON bundle");
        }
        support.sendLine(
                sender,
                "&7List/filter limits are 1-100 (default 20); exact-run inspection is fixed to one; time windows are at most 366 days.");
        support.sendLine(
                sender,
                "&7Names are never ownership filters. Bundle export also requires the export permission.");
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
        if (!acquire(sender, "run.investigation.query", QUERY_COOLDOWN)) {
            return;
        }
        support.completeOnMainThread(
                support.scores.run(runId),
                retained -> {
                    audit(
                            sender,
                            "run:" + runId,
                            retained.isPresent() ? 1 : 0);
                    if (!stillAuthorized(sender)) {
                        support.sendLine(
                                sender,
                                "&cInvestigation authorization changed before the result was displayed.");
                        return;
                    }
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
                    exactTextFilter("arena", arenaId));
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

    void release(CommandSender sender, String release, int limit) {
        if (!authorized(sender)) {
            return;
        }
        try {
            list(
                    sender,
                    RunInvestigationQuery.forRelease(release, limit),
                    exactTextFilter("release", release));
        } catch (IllegalArgumentException invalidRelease) {
            support.sendLine(
                    sender,
                    "&cRelease identities must be nonblank, contain no control characters, "
                            + "and use at most 128 characters.");
        }
    }

    void started(
            CommandSender sender,
            Instant fromInclusive,
            Instant beforeExclusive,
            int limit) {
        if (!authorized(sender)) {
            return;
        }
        try {
            list(
                    sender,
                    RunInvestigationQuery.forStartWindow(
                            fromInclusive, beforeExclusive, limit),
                    startFilter(fromInclusive, beforeExclusive));
        } catch (IllegalArgumentException invalidWindow) {
            sendInvalidWindow(sender);
        }
    }

    void exportList(
            CommandSender sender,
            Optional<RunStatus> status,
            int limit) {
        if (!authorizedForExport(sender)) {
            return;
        }
        RunInvestigationQuery query = status
                .map(value -> RunInvestigationQuery.forStatus(value, limit))
                .orElseGet(() -> RunInvestigationQuery.all(limit));
        String filter = status
                .map(value -> "status:" + value)
                .orElse("all");
        export(sender, query, filter);
    }

    void exportInspect(CommandSender sender, UUID runId) {
        if (authorizedForExport(sender)) {
            export(
                    sender,
                    RunInvestigationQuery.forRun(runId),
                    "run:" + runId);
        }
    }

    void exportPlayer(CommandSender sender, UUID playerId, int limit) {
        if (authorizedForExport(sender)) {
            export(
                    sender,
                    RunInvestigationQuery.forPlayer(playerId, limit),
                    "player:" + playerId);
        }
    }

    void exportArena(CommandSender sender, String arenaId, int limit) {
        if (!authorizedForExport(sender)) {
            return;
        }
        try {
            export(
                    sender,
                    RunInvestigationQuery.forArena(arenaId, limit),
                    exactTextFilter("arena", arenaId));
        } catch (IllegalArgumentException invalidArena) {
            support.sendLine(
                    sender,
                    "&cArena IDs must be nonblank, contain no control characters, "
                            + "and use at most 128 characters.");
        }
    }

    void exportSeason(CommandSender sender, UUID seasonId, int limit) {
        if (authorizedForExport(sender)) {
            export(
                    sender,
                    RunInvestigationQuery.forSeason(seasonId, limit),
                    "season:" + seasonId);
        }
    }

    void exportRelease(CommandSender sender, String release, int limit) {
        if (!authorizedForExport(sender)) {
            return;
        }
        try {
            export(
                    sender,
                    RunInvestigationQuery.forRelease(release, limit),
                    exactTextFilter("release", release));
        } catch (IllegalArgumentException invalidRelease) {
            support.sendLine(
                    sender,
                    "&cRelease identities must be nonblank, contain no control characters, "
                            + "and use at most 128 characters.");
        }
    }

    void exportStarted(
            CommandSender sender,
            Instant fromInclusive,
            Instant beforeExclusive,
            int limit) {
        if (!authorizedForExport(sender)) {
            return;
        }
        try {
            export(
                    sender,
                    RunInvestigationQuery.forStartWindow(
                            fromInclusive, beforeExclusive, limit),
                    startFilter(fromInclusive, beforeExclusive));
        } catch (IllegalArgumentException invalidWindow) {
            sendInvalidWindow(sender);
        }
    }

    private boolean authorized(CommandSender sender) {
        return support.requireAdministrativePermission(
                sender, support.permissions().adminInvestigate());
    }

    private boolean authorizedForExport(CommandSender sender) {
        return authorized(sender)
                && support.requireAdministrativePermission(
                        sender, support.permissions().adminExport());
    }

    private void list(
            CommandSender sender,
            RunInvestigationQuery query,
            String filter) {
        if (!acquire(sender, "run.investigation.query", QUERY_COOLDOWN)) {
            return;
        }
        support.completeOnMainThread(
                support.scores.investigateRuns(query),
                runs -> {
                    audit(sender, filter, runs.size());
                    if (!stillAuthorized(sender)) {
                        support.sendLine(
                                sender,
                                "&cInvestigation authorization changed before the results were displayed.");
                        return;
                    }
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

    private void export(
            CommandSender sender,
            RunInvestigationQuery query,
            String filter) {
        if (!acquire(sender, "run.investigation.export", EXPORT_COOLDOWN)) {
            return;
        }
        String safeFilter = CommandSupport.safeEvidenceText(filter, 160);
        UUID operatorId = CommandSupport.operatorId(sender);
        String actor = CommandSupport.operatorKind(sender);
        support.sendLine(sender, "&7Collecting bounded retained-run evidence...");
        support.scores.investigateRuns(query).whenComplete((runs, queryFailure) -> {
            if (queryFailure != null) {
                support.plugin.getLogger().log(
                        Level.WARNING,
                        "Could not query retained-run evidence for export",
                        queryFailure);
                auditExport(
                        operatorId,
                        actor,
                        safeFilter,
                        0,
                        "query_failed",
                        null,
                        null);
                support.scheduleCommandReply(
                        sender,
                        () -> support.sendLine(
                                sender,
                                "&cThe investigation query failed safely; no export was written."));
                return;
            }
            List<RunInvestigationRecord> snapshot = List.copyOf(runs);
            support.scheduleCommandReply(sender, () -> beginExport(
                    sender,
                    operatorId,
                    actor,
                    query,
                    safeFilter,
                    snapshot));
        });
    }

    private void beginExport(
            CommandSender sender,
            UUID operatorId,
            String actor,
            RunInvestigationQuery query,
            String filter,
            List<RunInvestigationRecord> runs) {
        audit(sender, filter, runs.size());
        if (!stillAuthorizedForExport(sender)) {
            auditExport(
                    operatorId,
                    actor,
                    filter,
                    runs.size(),
                    "authorization_changed",
                    null,
                    null);
            support.sendLine(
                    sender,
                    "&cInvestigation export authorization changed before file preparation.");
            return;
        }
        support.sendLine(sender, "&7Writing one atomic privacy-safe JSON bundle...");
        Instant capturedAt = Instant.now();
        support.operations.submitRequired(() -> {
                    if (!support.awaitMainApproval(
                            () -> stillAuthorizedForExport(sender))) {
                        throw new SecurityException(
                                "Investigation export authorization changed before execution");
                    }
                    RunInvestigationExportService.ExportResult result = exports.export(
                            runs,
                            query,
                            capturedAt,
                            support.buildInfo);
                    try {
                        support.operations.auditRequiredOnWorker(
                                "run.investigation_export",
                                operatorId,
                                null,
                                exportAuditFields(
                                        actor,
                                        filter,
                                        result.rows(),
                                        "exported",
                                        result.fileName(),
                                        result.sha256()));
                    } catch (IOException | RuntimeException auditFailure) {
                        throw new RunInvestigationExportCommitUncertainException(
                                result.fileName(), result.sha256(), auditFailure);
                    }
                    return result;
                })
                .whenComplete((result, failure) -> {
                    if (failure == null) {
                        support.scheduleCommandReply(sender, () -> {
                            support.sendLine(
                                    sender,
                                    "&aExported &f{{rows}}&a retained run(s).",
                                    Map.of("rows", result.rows()));
                            support.sendField(sender, "JSON", result.fileName());
                            support.sendField(sender, "SHA-256", result.sha256());
                        });
                        return;
                    }
                    Throwable cause = unwrap(failure);
                    String resultCategory = exportFailureCategory(cause);
                    String uncertainFile = cause
                            instanceof RunInvestigationExportCommitUncertainException uncertain
                                    ? uncertain.fileName()
                                    : null;
                    String uncertainSha = cause
                            instanceof RunInvestigationExportCommitUncertainException uncertain
                                    ? uncertain.sha256()
                                    : null;
                    auditExport(
                            operatorId,
                            actor,
                            filter,
                            runs.size(),
                            resultCategory,
                            uncertainFile,
                            uncertainSha);
                    support.plugin.getLogger().log(
                            Level.SEVERE,
                            "Could not export retained-run evidence ("
                                    + resultCategory + ')',
                            failure);
                    support.scheduleCommandReply(sender, () -> {
                        if (resultCategory.equals("commit_uncertain")) {
                            support.sendLine(
                                    sender,
                                    "&cThe final JSON or its audit binding may exist but is commit-uncertain; inspect exports before retrying.");
                            support.sendField(sender, "JSON", uncertainFile);
                            support.sendField(sender, "Expected SHA-256", uncertainSha);
                        } else {
                            support.sendLine(
                                    sender,
                                    "&cThe investigation export failed safely; see the console for its error category.");
                        }
                    });
                });
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
                        "actor", CommandSupport.operatorKind(sender),
                        "filter",
                        CommandSupport.safeEvidenceText(filter, 160),
                        "results", results));
    }

    private void auditExport(
            UUID operatorId,
            String actor,
            String filter,
            int results,
            String result,
            String fileName,
            String sha256) {
        support.operations.audit(
                "run.investigation_export",
                operatorId,
                null,
                exportAuditFields(actor, filter, results, result, fileName, sha256));
    }

    private static Map<String, Object> exportAuditFields(
            String actor,
            String filter,
            int results,
            String result,
            String fileName,
            String sha256) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor", actor);
        fields.put("filter", CommandSupport.safeEvidenceText(filter, 160));
        fields.put("results", results);
        fields.put("result", result);
        fields.put("schema", RunInvestigationExportService.SCHEMA_VERSION);
        if (fileName != null) {
            fields.put("file_name", CommandSupport.safeEvidenceText(fileName, 160));
        }
        if (sha256 != null) {
            fields.put("sha256", sha256);
        }
        return Map.copyOf(fields);
    }

    private boolean stillAuthorized(CommandSender sender) {
        return support.stillAuthorized(
                sender, support.permissions().adminInvestigate());
    }

    private boolean stillAuthorizedForExport(CommandSender sender) {
        return support.stillAuthorized(
                        sender, support.permissions().adminInvestigate())
                && support.stillAuthorized(
                        sender, support.permissions().adminExport());
    }

    private boolean acquire(
            CommandSender sender,
            String action,
            Duration cooldown) {
        UUID key = Optional.ofNullable(CommandSupport.operatorId(sender))
                .orElse(SYSTEM_RATE_LIMIT_KEY);
        ActionRateLimiter.Result result = rateLimiter.tryAcquire(
                key, action, cooldown, Instant.now());
        if (result.allowed()) {
            return true;
        }
        long retrySeconds = Math.max(
                1L, (result.retryAfter().toMillis() + 999L) / 1_000L);
        support.sendLine(
                sender,
                "&eThat investigation operation is rate-limited; retry in {{seconds}} second(s).",
                Map.of("seconds", retrySeconds));
        return false;
    }

    private static String startFilter(Instant start, Instant before) {
        return "started:[" + start + ',' + before + ')';
    }

    private static String exactTextFilter(String kind, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return kind + "_sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private void sendInvalidWindow(CommandSender sender) {
        support.sendLine(
                sender,
                "&cThe inclusive start must be before the exclusive end, and the complete window must not exceed 366 days.");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String exportFailureCategory(Throwable failure) {
        if (failure instanceof RunInvestigationExportCommitUncertainException) {
            return "commit_uncertain";
        }
        if (failure instanceof SecurityException) {
            return "authorization_changed";
        }
        if (failure instanceof RejectedExecutionException) {
            return "operations_queue_rejected";
        }
        if (failure instanceof IOException) {
            return "io_failed";
        }
        if (failure instanceof IllegalArgumentException) {
            return "validation_failed";
        }
        return "unexpected_failure";
    }
}
