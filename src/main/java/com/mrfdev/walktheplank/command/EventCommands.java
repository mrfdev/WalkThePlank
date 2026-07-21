package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.config.ConfigurationManager;
import com.mrfdev.walktheplank.config.ConfigurationValidationReport;
import com.mrfdev.walktheplank.config.EventConfigurationEditor;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;

/** Durable in-game control of the manual event participation switch. */
final class EventCommands {
    private final CommandSupport support;
    private final ConfigurationManager configuration;
    private final WalkCommand.ReloadHandler reloadHandler;
    private final EventConfigurationEditor editor;
    private final AtomicReference<EventConfigurationEditor.CommittedEdit> committedEdit =
            new AtomicReference<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Boolean>> shutdownReconciliation =
            new AtomicReference<>();

    EventCommands(
            CommandSupport support,
            ConfigurationManager configuration,
            WalkCommand.ReloadHandler reloadHandler) {
        this.support = support;
        this.configuration = configuration;
        this.reloadHandler = reloadHandler;
        editor = new EventConfigurationEditor(support.plugin, configuration);
    }

    void status(CommandSender sender) {
        if (!authorized(sender)) {
            return;
        }
        boolean enabled = support.settings.get().eventEnabled();
        support.sendHeader(sender, "Event participation");
        support.sendField(sender, "Runtime", enabled ? "OPEN" : "CLOSED");
        support.sendLine(
                sender,
                enabled
                        ? "&aPlayers may start new WalkThePlank runs."
                        : "&eThe plugin is loaded, but new runs are disabled.");
    }

    void setEnabled(CommandSender sender, boolean enabled) {
        if (!authorized(sender)) {
            return;
        }
        if (!support.configurationMutationPending.compareAndSet(false, true)) {
            support.sendLine(sender, "&eA configuration mutation is already in progress.");
            return;
        }
        long expectedGeneration = configuration.generation();
        support.sendLine(
                sender,
                "&7Persisting event.enabled={{enabled}} and validating before reload...",
                Map.of("enabled", enabled));
        support.operations.submitRequired(configuration::captureFiles)
                .whenComplete((files, failure) ->
                        support.scheduleCommandReply(sender, () -> {
                            if (failure != null) {
                                fail(sender, enabled, "capture_failed",
                                        "The configuration files could not be read safely.", failure);
                                return;
                            }
                            prepare(sender, enabled, expectedGeneration, files);
                        }));
    }

    CompletableFuture<Boolean> prepareShutdown() {
        closing.set(true);
        CompletableFuture<Boolean> existing = shutdownReconciliation.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Boolean> reconciliation = support.operations.submitRequired(() -> {
            EventConfigurationEditor.CommittedEdit committed = committedEdit.getAndSet(null);
            if (committed == null) {
                support.configurationMutationPending.set(false);
                return true;
            }
            try {
                editor.rollback(committed);
                support.configurationMutationPending.set(false);
                return true;
            } catch (IOException failure) {
                support.plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not reconcile a committed event switch during shutdown",
                        failure);
                return false;
            }
        });
        if (shutdownReconciliation.compareAndSet(null, reconciliation)) {
            return reconciliation;
        }
        return shutdownReconciliation.get();
    }

    private void prepare(
            CommandSender sender,
            boolean enabled,
            long expectedGeneration,
            ConfigurationManager.ConfigurationFiles files) {
        if (!revalidate(sender, expectedGeneration)) {
            fail(sender, enabled, "stale", "The event change became stale.", null);
            return;
        }
        EventConfigurationEditor.EditPreparation preparation;
        try {
            preparation = editor.prepare(files, expectedGeneration, enabled);
        } catch (IOException | RuntimeException failure) {
            fail(sender, enabled, "validation_failed",
                    "The event change could not be validated safely.", failure);
            return;
        }
        if (preparation.prepared().isEmpty()) {
            support.configurationMutationPending.set(false);
            audit(sender, enabled, "rejected", preparation.validation().fingerprint());
            sendValidationFailure(sender, preparation.validation());
            return;
        }
        EventConfigurationEditor.PreparedEdit prepared =
                preparation.prepared().orElseThrow();
        support.operations.submitRequired(() -> {
                    if (!support.awaitMainApproval(
                            () -> revalidate(sender, expectedGeneration))) {
                        throw new IllegalStateException(
                                "Event change became stale before durable commit");
                    }
                    EventConfigurationEditor.CommittedEdit committed = editor.commit(prepared);
                    if (!committedEdit.compareAndSet(null, committed)) {
                        editor.rollback(committed);
                        throw new IllegalStateException(
                                "Another event change is awaiting reconciliation");
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
                .whenComplete((committed, failure) ->
                        support.scheduleCommandReply(sender, () ->
                                beginReload(
                                        sender,
                                        enabled,
                                        expectedGeneration,
                                        committed,
                                        failure)));
    }

    private void beginReload(
            CommandSender sender,
            boolean enabled,
            long expectedGeneration,
            EventConfigurationEditor.CommittedEdit committed,
            Throwable failure) {
        if (failure != null || committed == null || committedEdit.get() != committed) {
            fail(sender, enabled, "commit_failed",
                    "event.enabled was not changed; persistence was rejected.", failure);
            return;
        }
        CompletableFuture<Boolean> reload;
        try {
            reload = reloadHandler.reload(
                    () -> revalidateCommitted(sender, expectedGeneration, committed));
        } catch (RuntimeException | LinkageError reloadFailure) {
            rollback(sender, enabled, committed, "reload_start_failed", reloadFailure);
            return;
        }
        reload.whenComplete((reloaded, reloadFailure) ->
                support.scheduleCommandReply(sender, () -> {
                    if (reloadFailure != null || !Boolean.TRUE.equals(reloaded)) {
                        rollback(sender, enabled, committed, "reload_failed", reloadFailure);
                        return;
                    }
                    verifyActivated(sender, enabled, committed);
                }));
    }

    private void verifyActivated(
            CommandSender sender,
            boolean enabled,
            EventConfigurationEditor.CommittedEdit committed) {
        support.operations.submitRequired(() -> {
                    editor.requireCommittedUnchanged(committed);
                    return null;
                })
                .whenComplete((ignored, failure) ->
                        support.scheduleCommandReply(sender, () -> {
                            committedEdit.compareAndSet(committed, null);
                            support.configurationMutationPending.set(false);
                            if (failure != null) {
                                support.plugin.getLogger().log(
                                        Level.SEVERE,
                                        "config.yml changed after the event switch activated",
                                        failure);
                                audit(sender, enabled, "activated_file_changed", "unavailable");
                                support.sendLine(sender,
                                        "&cThe runtime changed, but config.yml changed again during verification. "
                                                + "Validate it before restart.");
                                return;
                            }
                            String fingerprint = committed.prepared()
                                    .validation().fingerprint();
                            audit(sender, enabled, "activated", fingerprint);
                            support.sendLine(
                                    sender,
                                    enabled
                                            ? "&aWalkThePlank is now OPEN. Players may start runs."
                                            : "&aWalkThePlank is now CLOSED. Active work was safely drained.");
                            support.sendField(sender, "Config hash", "sha256:" + fingerprint);
                            support.sendField(
                                    sender,
                                    "Warnings",
                                    Integer.toString(committed.prepared()
                                            .validation().warnings().size()));
                        }));
    }

    private void rollback(
            CommandSender sender,
            boolean enabled,
            EventConfigurationEditor.CommittedEdit committed,
            String result,
            Throwable cause) {
        if (!committedEdit.compareAndSet(committed, null)) {
            support.configurationMutationPending.set(false);
            return;
        }
        support.operations.submitRequired(() -> {
                    editor.rollback(committed);
                    return null;
                })
                .whenComplete((ignored, rollbackFailure) ->
                        support.scheduleCommandReply(sender, () -> {
                            support.configurationMutationPending.set(false);
                            if (cause != null) {
                                support.plugin.getLogger().log(
                                        Level.WARNING,
                                        "Could not activate the event participation change",
                                        cause);
                            }
                            if (rollbackFailure == null) {
                                audit(sender, enabled, result + "_rolled_back", "unavailable");
                                support.sendLine(sender,
                                        "&cThe event change could not activate; config.yml was restored.");
                                return;
                            }
                            audit(sender, enabled, "rollback_failed", "unavailable");
                            support.plugin.getLogger().log(
                                    Level.SEVERE,
                                    "Could not restore config.yml after the event change failed",
                                    rollbackFailure);
                            support.sendLine(sender,
                                    "&cThe event change and rollback both failed; stop and inspect the console.");
                        }));
    }

    private boolean authorized(CommandSender sender) {
        return support.requireAdministrativePermission(
                sender, support.permissions().reload());
    }

    private boolean revalidate(CommandSender sender, long expectedGeneration) {
        return editStateCurrent(
                expectedGeneration,
                configuration.generation(),
                support.stillAuthorized(sender, support.permissions().reload()),
                closing.get());
    }

    static boolean editStateCurrent(
            long expectedGeneration,
            long currentGeneration,
            boolean authorized,
            boolean closing) {
        return !closing && authorized && expectedGeneration == currentGeneration;
    }

    private boolean revalidateCommitted(
            CommandSender sender,
            long expectedGeneration,
            EventConfigurationEditor.CommittedEdit committed) {
        return revalidate(sender, expectedGeneration) && committedEdit.get() == committed;
    }

    private void fail(
            CommandSender sender,
            boolean enabled,
            String result,
            String message,
            Throwable failure) {
        support.configurationMutationPending.set(false);
        audit(sender, enabled, result, "unavailable");
        if (failure != null) {
            support.plugin.getLogger().log(
                    Level.WARNING,
                    "Could not complete the event participation change",
                    failure);
        }
        support.sendLine(sender, "&c{{error}}", Map.of("error", message));
    }

    private void sendValidationFailure(
            CommandSender sender,
            ConfigurationValidationReport validation) {
        support.sendHeader(sender, "Event change rejected");
        for (String error : validation.errors()) {
            support.sendLine(sender, "&cERROR &7{{error}}", Map.of("error", error));
        }
        support.sendField(sender, "Warnings", Integer.toString(validation.warnings().size()));
    }

    private void audit(
            CommandSender sender,
            boolean enabled,
            String result,
            String fingerprint) {
        support.operations.audit(
                "admin.event",
                CommandSupport.operatorId(sender),
                null,
                Map.of(
                        "actor", CommandSupport.operatorKind(sender),
                        "enabled", enabled,
                        "result", result,
                        "fingerprint", fingerprint));
    }
}
