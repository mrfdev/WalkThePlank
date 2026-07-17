package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.config.ArenaConfigurationEditor;
import com.mrfdev.walktheplank.config.ArenaId;
import com.mrfdev.walktheplank.config.ConfigurationManager;
import com.mrfdev.walktheplank.config.ConfigurationValidationReport;
import com.mrfdev.walktheplank.game.Arena;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Transactional arena configuration editing and read-only validation. */
final class ArenaCommands {
    enum LocationEdit {
        CREATE("create", "Created"),
        SET_START("setstart", "Updated the start for"),
        SET_EXIT("setexit", "Updated the exit for");

        private final String command;
        private final String successVerb;

        LocationEdit(String command, String successVerb) {
            this.command = command;
            this.successVerb = successVerb;
        }
    }

    private final CommandSupport support;
    private final ConfigurationManager configuration;
    private final WalkCommand.ConfigurationActivator configurationActivator;
    private final ArenaConfigurationEditor editor;
    private final AtomicReference<ArenaConfigurationEditor.CommittedEdit> committedEdit =
            new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Boolean>> shutdownReconciliation =
            new AtomicReference<>();

    ArenaCommands(
            CommandSupport support,
            ConfigurationManager configuration,
            WalkCommand.ConfigurationActivator configurationActivator) {
        this.support = support;
        this.configuration = configuration;
        this.configurationActivator = configurationActivator;
        this.editor = new ArenaConfigurationEditor(support.plugin, configuration);
    }

    void help(CommandSender sender) {
        if (!authorized(sender)) {
            return;
        }
        support.sendHeader(sender, "Arena editor");
        support.sendCommandHelp(
                sender, "/walk admin arena list", "List configured arenas");
        support.sendCommandHelp(
                sender,
                "/walk admin arena create <id>",
                "Create an arena at your block");
        support.sendCommandHelp(
                sender,
                "/walk admin arena setstart <id>",
                "Capture your block as its start");
        support.sendCommandHelp(
                sender,
                "/walk admin arena setexit <id>",
                "Capture your location as its exit");
        support.sendCommandHelp(
                sender,
                "/walk admin arena clear-exit <id>",
                "Return players to their saved location");
        support.sendCommandHelp(
                sender,
                "/walk admin arena validate [id]",
                "Validate without changing anything");
        support.sendCommandHelp(
                sender,
                "/walk admin arena remove <id> confirm",
                "Remove with explicit confirmation");
    }

    void list(CommandSender sender) {
        if (!authorized(sender)) {
            return;
        }
        List<Arena> arenas = support.settings.get().arenas();
        support.sendHeader(sender, "Active configured arenas");
        for (Arena arena : arenas) {
            Location start = arena.start();
            support.sendLine(
                    sender,
                    "&3{{arenaId}} &7- &f{{world}} @ {{position}} &7({{exitMode}})",
                    Map.of(
                            "arenaId", arena.id(),
                            "world", java.util.Objects.requireNonNull(
                                    start.getWorld(), "arena world").getName(),
                            "position", start.getBlockX() + ","
                                    + start.getBlockY() + ","
                                    + start.getBlockZ(),
                            "exitMode",
                            arena.exit() != null
                                    ? "custom exit"
                                    : "saved return"));
        }
        support.sendField(sender, "Total", Integer.toString(arenas.size()));
    }

    void locationEdit(
            CommandSender sender,
            String id,
            LocationEdit edit) {
        if (!authorized(sender)) {
            return;
        }
        Player player = support.requirePlayer(sender);
        if (player == null || !requireIdle(sender)) {
            return;
        }
        ArenaConfigurationEditor.LocationData location =
                ArenaConfigurationEditor.LocationData.capture(
                        player.getLocation());
        applyEdit(
                sender,
                id,
                edit.command,
                edit.successVerb + " arena '" + id + "'",
                (files, generation) -> switch (edit) {
                    case CREATE -> editor.prepareCreate(
                            files, generation, id, location);
                    case SET_START -> editor.prepareSetStart(
                            files, generation, id, location);
                    case SET_EXIT -> editor.prepareSetExit(
                            files, generation, id, location);
                });
    }

    void clearExit(CommandSender sender, String id) {
        if (!authorized(sender) || !requireIdle(sender)) {
            return;
        }
        applyEdit(
                sender,
                id,
                "clear-exit",
                "Cleared the custom exit for arena '" + id + "'",
                (files, generation) ->
                        editor.prepareClearExit(files, generation, id));
    }

    void remove(CommandSender sender, String id) {
        if (!authorized(sender) || !requireIdle(sender)) {
            return;
        }
        applyEdit(
                sender,
                id,
                "remove",
                "Removed arena '" + id + "'",
                (files, generation) ->
                        editor.prepareRemove(files, generation, id));
    }

    void validateAll(CommandSender sender) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminValidate())) {
            return;
        }
        validateCaptured(
                sender,
                "configuration",
                null,
                support.permissions().adminValidate(),
                "Configuration validation");
    }

    void validateArenas(CommandSender sender) {
        if (!authorized(sender)) {
            return;
        }
        validateCaptured(
                sender,
                "arena",
                null,
                support.permissions().adminArena(),
                "Arena configuration validation");
    }

    void validateArena(CommandSender sender, String requestedArenaId) {
        if (!authorized(sender)) {
            return;
        }
        String arenaId;
        try {
            arenaId = ArenaId.requireValid(requestedArenaId);
        } catch (IllegalArgumentException exception) {
            support.sendLine(
                    sender,
                    "&c{{error}}",
                    Map.of("error", String.valueOf(exception.getMessage())));
            return;
        }
        validateCaptured(
                sender,
                "arena",
                arenaId,
                support.permissions().adminArena(),
                "Arena configuration validation");
    }

    CompletableFuture<Boolean> prepareShutdown() {
        CompletableFuture<Boolean> existing = shutdownReconciliation.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Boolean> reconciliation =
                support.operations.submitRequired(() -> {
                    ArenaConfigurationEditor.CommittedEdit committed =
                            committedEdit.get();
                    if (committed == null) {
                        support.configurationMutationPending.set(false);
                        return true;
                    }
                    try {
                        editor.rollback(committed);
                        committedEdit.compareAndSet(committed, null);
                        support.configurationMutationPending.set(false);
                        return true;
                    } catch (IOException rollbackFailure) {
                        support.plugin.getLogger().log(
                                Level.SEVERE,
                                "Could not reconcile a committed arena edit "
                                        + "during shutdown",
                                rollbackFailure);
                        return false;
                    }
                });
        if (shutdownReconciliation.compareAndSet(null, reconciliation)) {
            return reconciliation;
        }
        return shutdownReconciliation.get();
    }

    static boolean editStateCurrent(
            long expectedGeneration,
            long currentGeneration,
            boolean authorized,
            boolean idle) {
        return authorized
                && idle
                && expectedGeneration == currentGeneration;
    }

    private boolean authorized(CommandSender sender) {
        return support.requireAdministrativePermission(
                sender, support.permissions().adminArena());
    }

    private boolean requireIdle(CommandSender sender) {
        if (support.games.isConfigurationMutationIdle()) {
            return true;
        }
        support.sendLine(
                sender,
                "&cArena configuration requires an empty queue and no active, pending, "
                        + "quarantined, or recovery-owned arena work.");
        return false;
    }

    private void validateCaptured(
            CommandSender sender,
            String scope,
            String arenaId,
            String permission,
            String title) {
        support.sendLine(
                sender,
                "&7Reading configuration asynchronously for validation...");
        support.operations.submitRequired(configuration::captureFiles)
                .whenComplete((files, failure) ->
                        support.scheduleCommandReply(sender, () -> {
                            if (failure != null) {
                                support.plugin.getLogger().log(
                                        Level.WARNING,
                                        "Could not capture configuration for validation",
                                        failure);
                                support.sendLine(
                                        sender,
                                        "&cThe configuration files could not be read safely.");
                                return;
                            }
                            if (!support.stillAuthorized(sender, permission)) {
                                support.sendLine(
                                        sender,
                                        "&cAuthorization changed before validation completed.");
                                return;
                            }
                            if (!validateRequestedArena(sender, files, arenaId)) {
                                return;
                            }
                            validateFiles(
                                    sender,
                                    files,
                                    scope,
                                    arenaId,
                                    permission,
                                    title);
                        }));
    }

    private boolean validateRequestedArena(
            CommandSender sender,
            ConfigurationManager.ConfigurationFiles files,
            String arenaId) {
        if (arenaId == null) {
            return true;
        }
        try {
            if (!editor.contains(files, arenaId)) {
                support.sendLine(
                        sender,
                        "&cArena '{{arenaId}}' does not exist.",
                        Map.of("arenaId", arenaId));
                return false;
            }
            support.sendLine(
                    sender,
                    "&7Checking arena &f{{arenaId}}&7 within the complete arena layout.",
                    Map.of("arenaId", arenaId));
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            support.plugin.getLogger().log(
                    Level.WARNING,
                    "Could not parse an arena for validation",
                    exception);
            support.sendLine(
                    sender,
                    "&cThe captured arena list could not be parsed safely.");
            return false;
        }
    }

    private void validateFiles(
            CommandSender sender,
            ConfigurationManager.ConfigurationFiles files,
            String scope,
            String arenaId,
            String permission,
            String title) {
        try {
            ConfigurationValidationReport report =
                    configuration.validate(files);
            if (!report.valid()) {
                auditValidation(sender, scope, arenaId, report);
                sendValidationReport(sender, title, report);
                return;
            }
            ConfigurationManager.ConfigurationSnapshot candidate =
                    configuration.prepare(files);
            support.operations.submitRequired(() -> {
                        configuration.requireFilesUnchanged(files);
                        configuration.requireDatabaseStorageSafe(
                                candidate.databaseSettings());
                        return null;
                    })
                    .whenComplete((ignored, storageFailure) ->
                            support.scheduleCommandReply(sender, () -> {
                                if (!support.stillAuthorized(
                                        sender, permission)) {
                                    support.sendLine(
                                            sender,
                                            "&cAuthorization changed before "
                                                    + "validation completed.");
                                    return;
                                }
                                ConfigurationValidationReport completed =
                                        withStorageFailure(
                                                report, storageFailure);
                                auditValidation(
                                        sender,
                                        scope,
                                        arenaId,
                                        completed);
                                sendValidationReport(
                                        sender, title, completed);
                            }));
        } catch (IOException | RuntimeException | LinkageError failure) {
            support.plugin.getLogger().log(
                    Level.WARNING,
                    "Could not validate captured configuration",
                    failure);
            support.sendLine(
                    sender,
                    "&cThe captured configuration could not be validated safely.");
        }
    }

    private ConfigurationValidationReport withStorageFailure(
            ConfigurationValidationReport report,
            Throwable storageFailure) {
        if (storageFailure == null) {
            return report;
        }
        support.plugin.getLogger().log(
                Level.WARNING,
                "Configuration snapshot or SQLite storage failed final validation",
                storageFailure);
        List<String> errors = new ArrayList<>(report.errors());
        errors.add(
                "Configuration changed during validation or SQLite storage "
                        + "is not a safe usable target");
        return new ConfigurationValidationReport(
                errors, report.warnings(), report.fingerprint());
    }

    private void applyEdit(
            CommandSender sender,
            String arenaId,
            String editAction,
            String successMessage,
            ArenaEdit action) {
        if (!support.configurationMutationPending.compareAndSet(false, true)) {
            support.sendLine(
                    sender,
                    "&eA configuration mutation is already in progress.");
            return;
        }
        long expectedGeneration = configuration.generation();
        support.sendLine(
                sender,
                "&7Capturing and validating the arena edit asynchronously...");
        support.operations.submitRequired(configuration::captureFiles)
                .whenComplete((files, captureFailure) ->
                        support.scheduleCommandReply(sender, () -> {
                            if (captureFailure != null) {
                                finishFailure(
                                        sender,
                                        arenaId,
                                        editAction,
                                        "io_failed",
                                        "unavailable",
                                        "The configuration files could not "
                                                + "be read safely.",
                                        captureFailure);
                                return;
                            }
                            prepareEdit(
                                    sender,
                                    arenaId,
                                    editAction,
                                    successMessage,
                                    expectedGeneration,
                                    files,
                                    action);
                        }));
    }

    private void prepareEdit(
            CommandSender sender,
            String arenaId,
            String editAction,
            String successMessage,
            long expectedGeneration,
            ConfigurationManager.ConfigurationFiles files,
            ArenaEdit action) {
        if (!revalidate(sender, expectedGeneration)) {
            finishFailure(
                    sender,
                    arenaId,
                    editAction,
                    "stale",
                    "unavailable",
                    "The arena edit became stale before validation completed.",
                    null);
            return;
        }
        ArenaConfigurationEditor.EditPreparation preparation;
        try {
            preparation = action.prepare(files, expectedGeneration);
        } catch (IllegalArgumentException exception) {
            finishFailure(
                    sender,
                    arenaId,
                    editAction,
                    "invalid",
                    "unavailable",
                    String.valueOf(exception.getMessage()),
                    null);
            return;
        } catch (IOException | RuntimeException exception) {
            finishFailure(
                    sender,
                    arenaId,
                    editAction,
                    "validation_failed",
                    "unavailable",
                    "The captured configuration could not be validated safely.",
                    exception);
            return;
        }
        if (preparation.prepared().isEmpty()) {
            support.configurationMutationPending.set(false);
            support.games.auditArenaEdit(
                    CommandSupport.operatorId(sender),
                    arenaId,
                    editAction,
                    "rejected",
                    preparation.validation().fingerprint());
            sendValidationReport(
                    sender,
                    "Candidate rejected; config.yml was not changed",
                    preparation.validation());
            return;
        }
        ArenaConfigurationEditor.PreparedEdit prepared =
                preparation.prepared().orElseThrow();
        if (!revalidate(sender, expectedGeneration)) {
            finishFailure(
                    sender,
                    arenaId,
                    editAction,
                    "stale",
                    prepared.validation().fingerprint(),
                    "The arena edit became stale before persistence.",
                    null);
            return;
        }
        commitPrepared(
                sender,
                arenaId,
                editAction,
                successMessage,
                expectedGeneration,
                prepared);
    }

    private void commitPrepared(
            CommandSender sender,
            String arenaId,
            String editAction,
            String successMessage,
            long expectedGeneration,
            ArenaConfigurationEditor.PreparedEdit prepared) {
        support.operations.submitRequired(() -> {
                    if (!support.awaitMainApproval(
                            () -> revalidate(sender, expectedGeneration))) {
                        throw new IllegalStateException(
                                "Arena edit became stale before durable commit");
                    }
                    ArenaConfigurationEditor.CommittedEdit committed =
                            editor.commit(prepared);
                    if (!committedEdit.compareAndSet(null, committed)) {
                        editor.rollback(committed);
                        throw new IllegalStateException(
                                "Another committed arena edit is awaiting reconciliation");
                    }
                    try {
                        editor.requireCommittedUnchanged(committed);
                        return committed;
                    } catch (IOException verificationFailure) {
                        try {
                            editor.rollback(committed);
                            committedEdit.compareAndSet(committed, null);
                        } catch (IOException rollbackFailure) {
                            verificationFailure.addSuppressed(rollbackFailure);
                        }
                        throw verificationFailure;
                    }
                })
                .whenComplete((committed, commitFailure) ->
                        support.scheduleCommandReply(sender, () ->
                                finishCommitted(
                                        sender,
                                        arenaId,
                                        editAction,
                                        successMessage,
                                        expectedGeneration,
                                        committed,
                                        commitFailure)));
    }

    private void finishCommitted(
            CommandSender sender,
            String arenaId,
            String editAction,
            String successMessage,
            long expectedGeneration,
            ArenaConfigurationEditor.CommittedEdit committed,
            Throwable commitFailure) {
        if (commitFailure != null) {
            finishFailure(
                    sender,
                    arenaId,
                    editAction,
                    "io_failed",
                    "unavailable",
                    "The edit was not persisted; the files changed or "
                            + "the I/O queue rejected it.",
                    commitFailure);
            return;
        }
        if (committed == null || committedEdit.get() != committed) {
            finishFailure(
                    sender,
                    arenaId,
                    editAction,
                    "ownership_lost",
                    "unavailable",
                    "The committed edit lost its exact reconciliation ownership.",
                    new IllegalStateException(
                            "Committed arena edit ownership was lost"));
            return;
        }
        ArenaConfigurationEditor.PreparedEdit prepared = committed.prepared();
        ConfigurationManager.ConfigurationSnapshot previous =
                configuration.activeSnapshot();
        boolean activated = false;
        try {
            activated = revalidate(sender, expectedGeneration)
                    && configurationActivator.activate(
                            prepared.snapshot(),
                            expectedGeneration,
                            () -> revalidate(sender, expectedGeneration));
        } catch (RuntimeException | LinkageError activationFailure) {
            support.plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not activate a durably committed arena edit",
                    activationFailure);
        }
        if (activated) {
            support.operations.submitRequired(() -> {
                        editor.requireCommittedUnchanged(committed);
                        return null;
                    })
                    .whenComplete((ignored, verificationFailure) ->
                            support.scheduleCommandReply(sender, () ->
                                    finishVerified(
                                            sender,
                                            arenaId,
                                            editAction,
                                            successMessage,
                                            expectedGeneration,
                                            previous,
                                            committed,
                                            verificationFailure)));
            return;
        }
        queueRollback(
                sender,
                arenaId,
                editAction,
                prepared,
                committed,
                "The edit became stale or could not activate; "
                        + "config.yml was restored.");
    }

    private void finishVerified(
            CommandSender sender,
            String arenaId,
            String editAction,
            String successMessage,
            long expectedGeneration,
            ConfigurationManager.ConfigurationSnapshot previous,
            ArenaConfigurationEditor.CommittedEdit committed,
            Throwable verificationFailure) {
        ArenaConfigurationEditor.PreparedEdit prepared = committed.prepared();
        if (verificationFailure == null
                && configuration.activeSnapshot() == prepared.snapshot()
                && committedEdit.compareAndSet(committed, null)) {
            support.configurationMutationPending.set(false);
            support.games.auditArenaEdit(
                    CommandSupport.operatorId(sender),
                    arenaId,
                    editAction,
                    "activated",
                    prepared.validation().fingerprint());
            support.sendLine(
                    sender,
                    "&a{{successMessage}} and activated the validated configuration.",
                    Map.of("successMessage", successMessage));
            support.sendField(
                    sender,
                    "Config hash",
                    "sha256:" + prepared.validation().fingerprint());
            sendValidationWarnings(sender, prepared.validation());
            return;
        }
        if (verificationFailure != null) {
            support.plugin.getLogger().log(
                    Level.SEVERE,
                    "A committed arena edit changed during provisional "
                            + "runtime activation",
                    verificationFailure);
        }
        try {
            configurationActivator.activate(
                    previous,
                    expectedGeneration + 1L,
                    () -> configuration.generation() == expectedGeneration + 1L
                            && support.games.isConfigurationMutationIdle());
        } catch (RuntimeException | LinkageError rollbackFailure) {
            support.plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not restore the previous runtime after arena "
                            + "edit verification failed",
                    rollbackFailure);
        }
        queueRollback(
                sender,
                arenaId,
                editAction,
                prepared,
                committed,
                "The committed file changed during activation; the previous "
                        + "runtime and config.yml were restored where ownership "
                        + "still matched.");
    }

    private void queueRollback(
            CommandSender sender,
            String arenaId,
            String editAction,
            ArenaConfigurationEditor.PreparedEdit prepared,
            ArenaConfigurationEditor.CommittedEdit committed,
            String message) {
        support.operations.submitRequired(() -> {
                    editor.rollback(committed);
                    committedEdit.compareAndSet(committed, null);
                    return null;
                })
                .whenComplete((ignored, rollbackFailure) ->
                        support.scheduleCommandReply(sender, () -> {
                            support.configurationMutationPending.set(false);
                            if (rollbackFailure == null) {
                                support.games.auditArenaEdit(
                                        CommandSupport.operatorId(sender),
                                        arenaId,
                                        editAction,
                                        "rolled_back",
                                        prepared.validation().fingerprint());
                                support.sendLine(
                                        sender,
                                        "&c{{message}}",
                                        Map.of("message", message));
                                return;
                            }
                            support.games.auditArenaEdit(
                                    CommandSupport.operatorId(sender),
                                    arenaId,
                                    editAction,
                                    "rollback_failed",
                                    prepared.validation().fingerprint());
                            support.plugin.getLogger().log(
                                    Level.SEVERE,
                                    "Could not restore config.yml after an "
                                            + "arena edit failed to activate",
                                    rollbackFailure);
                            support.sendLine(
                                    sender,
                                    "&cThe edit and automatic rollback both failed; "
                                            + "stop edits and inspect the console.");
                        }));
    }

    private boolean revalidate(
            CommandSender sender,
            long expectedGeneration) {
        return editStateCurrent(
                expectedGeneration,
                configuration.generation(),
                support.stillAuthorized(
                        sender, support.permissions().adminArena()),
                support.games.isConfigurationMutationIdle());
    }

    private void finishFailure(
            CommandSender sender,
            String arenaId,
            String editAction,
            String result,
            String fingerprint,
            String playerMessage,
            Throwable failure) {
        support.configurationMutationPending.set(false);
        support.games.auditArenaEdit(
                CommandSupport.operatorId(sender),
                arenaId,
                editAction,
                result,
                fingerprint);
        if (failure != null) {
            support.plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not complete an arena configuration edit",
                    failure);
        }
        support.sendLine(
                sender,
                "&c{{error}}",
                Map.of("error", playerMessage));
    }

    private void sendValidationReport(
            CommandSender sender,
            String title,
            ConfigurationValidationReport report) {
        support.sendHeader(sender, title);
        support.sendField(
                sender,
                "Config hash",
                report.fingerprint().equals("unavailable")
                        ? "unavailable"
                        : "sha256:" + report.fingerprint());
        if (report.valid()) {
            support.sendLine(
                    sender,
                    "&aVALID &7- no configuration errors; "
                            + "active settings were not changed.");
        } else {
            support.sendLine(
                    sender,
                    "&cINVALID &7- {{errors}} error(s); "
                            + "active settings were not changed.",
                    Map.of("errors", report.errors().size()));
        }
        for (String error : report.errors()) {
            support.sendLine(
                    sender,
                    "&cERROR &7{{error}}",
                    Map.of("error", error));
        }
        sendValidationWarnings(sender, report);
    }

    private void auditValidation(
            CommandSender sender,
            String scope,
            String arenaId,
            ConfigurationValidationReport report) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor", CommandSupport.operatorKind(sender));
        fields.put("scope", scope);
        fields.put("result", report.valid() ? "valid" : "invalid");
        fields.put("errors", report.errors().size());
        fields.put("warnings", report.warnings().size());
        fields.put("fingerprint", report.fingerprint());
        if (arenaId != null) {
            fields.put("arena_id", arenaId);
        }
        support.operations.audit(
                "admin.validate",
                CommandSupport.operatorId(sender),
                null,
                Map.copyOf(fields));
    }

    private void sendValidationWarnings(
            CommandSender sender,
            ConfigurationValidationReport report) {
        for (String warning : report.warnings()) {
            support.sendLine(
                    sender,
                    "&eWARN &7{{warning}}",
                    Map.of("warning", warning));
        }
        support.sendField(
                sender,
                "Warnings",
                Integer.toString(report.warnings().size()));
    }

    @FunctionalInterface
    private interface ArenaEdit {
        ArenaConfigurationEditor.EditPreparation prepare(
                ConfigurationManager.ConfigurationFiles files,
                long expectedGeneration) throws IOException;
    }
}
