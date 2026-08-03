package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.build.BuildInfo;
import com.mrfdev.walktheplank.config.ConfigurationManager;
import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.game.GameManager;
import com.mrfdev.walktheplank.gui.MenuService;
import com.mrfdev.walktheplank.ops.OperationalContext;
import com.mrfdev.walktheplank.text.MessageService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lifecycle-registered Brigadier command coordinator.
 *
 * <p>Domain behavior lives in the player, queue, arena, season, reward, investigation, test, and
 * database modules. This class only coordinates registration, shutdown, reload, and shared help.
 */
public final class WalkCommand {
    final CommandSupport support;
    final PlayerCommands players;
    final QueueCommands queue;
    final ArenaCommands arenas;
    final EventCommands events;
    final SeasonCommands seasons;
    final RewardCommands rewards;
    final InvestigationCommands investigations;
    final TestCommands tests;
    final DatabaseCommands database;

    private final ReloadHandler reloadHandler;

    public WalkCommand(
            JavaPlugin plugin,
            Supplier<RuntimeSettings> settings,
            GameManager games,
            ScoreRepository scores,
            MenuService menus,
            MessageService messages,
            ReloadHandler reloadHandler,
            BuildInfo buildInfo,
            BooleanSupplier placeholderRegistered,
            OperationalContext operations,
            ConfigurationManager configuration,
            ConfigurationActivator configurationActivator) {
        this.support = new CommandSupport(
                plugin,
                settings,
                games,
                scores,
                menus,
                messages,
                buildInfo,
                placeholderRegistered,
                operations);
        this.reloadHandler = Objects.requireNonNull(
                reloadHandler, "reloadHandler");
        this.players = new PlayerCommands(support);
        this.queue = new QueueCommands(support);
        this.arenas = new ArenaCommands(
                support,
                Objects.requireNonNull(configuration, "configuration"),
                Objects.requireNonNull(
                        configurationActivator, "configurationActivator"));
        this.events = new EventCommands(support, configuration, this.reloadHandler);
        this.seasons = new SeasonCommands(support);
        this.rewards = new RewardCommands(support);
        this.investigations = new InvestigationCommands(support);
        this.tests = new TestCommands(support);
        this.database = new DatabaseCommands(support);
    }

    /** Registers the complete typed command tree and compatibility aliases. */
    public void register(Commands commands) {
        Objects.requireNonNull(commands, "commands").register(
                WalkCommandTree.create(this),
                "Open and control WalkThePlank.",
                WalkCommandTree.ALIASES);
    }

    /**
     * Stops new command work and queues exact reconciliation for any arena edit that reached
     * durable commit but not final activation.
     */
    public CompletableFuture<Boolean> prepareShutdown() {
        support.closeCommands();
        return arenas.prepareShutdown().thenCombine(
                events.prepareShutdown(),
                (arenaClean, eventClean) -> arenaClean && eventClean);
    }

    int execute(
            CommandSourceStack source,
            Consumer<CommandSender> action) {
        CommandSender sender = source.getSender();
        if (!support.begin(sender)) {
            return 0;
        }
        try {
            action.accept(sender);
            return 1;
        } catch (RuntimeException | LinkageError failure) {
            support.plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not execute a WalkThePlank command safely",
                    failure);
            support.sendLine(
                    sender,
                    "&cThe command failed safely; review the protected server log.");
            return 0;
        }
    }

    void reload(CommandSender sender) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().reload())) {
            return;
        }
        if (!support.configurationMutationPending.compareAndSet(false, true)) {
            support.sendLine(
                    sender,
                    "&eA configuration mutation is already in progress.");
            return;
        }
        var operatorId = CommandSupport.operatorId(sender);
        String actor = CommandSupport.operatorKind(sender);
        support.sendLine(
                sender,
                "&7Reading and validating configuration asynchronously...");
        CompletableFuture<Boolean> reload;
        try {
            reload = reloadHandler.reload(() -> support.stillAuthorized(
                    sender, support.permissions().reload()));
        } catch (RuntimeException | LinkageError failure) {
            support.configurationMutationPending.set(false);
            support.plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start the configuration reload",
                    failure);
            support.operations.audit(
                    "admin.reload",
                    operatorId,
                    null,
                    Map.of("actor", actor, "result", "start_failed"));
            support.messages.send(sender, "chat.reloadFailed");
            return;
        }
        reload.whenComplete((reloaded, failure) -> {
            support.configurationMutationPending.set(false);
            support.scheduleCommandReply(sender, () -> {
                boolean succeeded =
                        failure == null && Boolean.TRUE.equals(reloaded);
                if (failure != null) {
                    support.plugin.getLogger().log(
                            Level.SEVERE,
                            "Could not complete the configuration reload",
                            failure);
                }
                support.operations.audit(
                        "admin.reload",
                        operatorId,
                        null,
                        Map.of(
                                "actor",
                                actor,
                                "result",
                                succeeded ? "reloaded" : "failed"));
                support.messages.send(
                        sender,
                        succeeded
                                ? "chat.reloadSuccess"
                                : "chat.reloadFailed");
            });
        });
    }

    void adminHelp(CommandSender sender) {
        if (!support.hasAnyAdminPermission(sender)) {
            support.deny(sender, support.permissions().admin());
            return;
        }
        var permissions = support.permissions();
        support.sendHeader(sender, "Administration");
        support.addAdminHelp(
                sender,
                permissions.adminOpen(),
                "/walk admin open <player>",
                "Open a player's menu");
        support.addAdminHelp(
                sender,
                permissions.reload(),
                "/walk admin reload",
                "Reload and safely drain runs");
        support.addAdminHelp(
                sender,
                permissions.reload(),
                "/walk admin event <status|enable|disable>",
                "Persist and apply the event participation switch");
        support.addAdminHelp(
                sender,
                permissions.adminStop(),
                "/walk admin stop <player>",
                "Stop a run without rewards");
        support.addAdminHelp(
                sender,
                permissions.adminRecover(),
                "/walk admin recover",
                "Retry quarantined blocks and eligible player recovery");
        support.addAdminHelp(
                sender,
                permissions.adminValidate(),
                "/walk admin validate",
                "Validate config without applying it");
        support.addAdminHelp(
                sender,
                permissions.adminArena(),
                "/walk admin arena",
                "Safely manage configured arenas");
        support.addAdminHelp(
                sender,
                permissions.adminQueue(),
                "/walk admin queue",
                "Inspect, pause, or drain the queue");
        support.addAdminHelp(
                sender,
                permissions.adminSeason(),
                "/walk admin season",
                "Manage explicit event seasons");
        support.addAdminHelp(
                sender,
                permissions.adminExport(),
                "/walk admin export",
                "Write UUID-only CSV/JSON snapshots");
        support.addAdminHelp(
                sender,
                permissions.adminReward(),
                "/walk admin reward",
                "Inspect/resolve UNKNOWN rewards without replay");
        support.addAdminHelp(
                sender,
                permissions.adminInvestigate(),
                "/walk admin run",
                "Query redacted retained-run evidence");
        support.addAdminHelp(
                sender,
                permissions.adminTest(),
                "/walk admin test fast-forward <target-score>",
                "Start a self-only non-scoring reproduction run");
        support.addAdminHelp(
                sender,
                permissions.adminDebug(),
                "/walk admin status [player]",
                "Show arena or run status");
        support.addAdminHelp(
                sender,
                permissions.adminDebug(),
                "/walk admin debug [page]",
                "Show safe diagnostics");
        support.addAdminHelp(
                sender,
                permissions.adminDebug(),
                "/walk admin doctor",
                "Create a privacy-safe asynchronous support report");
    }

    boolean playerPermission(
            CommandSourceStack source,
            String permission) {
        return support.has(source.getSender(), permission);
    }

    boolean adminPermission(
            CommandSourceStack source,
            String permission) {
        return support.hasAdministrativePermission(
                source.getSender(), permission);
    }

    boolean anyAdminPermission(CommandSourceStack source) {
        return support.hasAnyAdminPermission(source.getSender());
    }

    static boolean arenaEditStateCurrent(
            long expectedGeneration,
            long currentGeneration,
            boolean authorized,
            boolean idle) {
        return ArenaCommands.editStateCurrent(
                expectedGeneration,
                currentGeneration,
                authorized,
                idle);
    }

    @FunctionalInterface
    public interface ReloadHandler {
        CompletableFuture<Boolean> reload(BooleanSupplier revalidate);
    }

    @FunctionalInterface
    public interface ConfigurationActivator {
        boolean activate(
                ConfigurationManager.ConfigurationSnapshot candidate,
                long expectedGeneration,
                BooleanSupplier revalidate);
    }
}
