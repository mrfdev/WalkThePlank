package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.build.BuildInfo;
import com.mrfdev.walktheplank.config.ArenaConfigurationEditor;
import com.mrfdev.walktheplank.config.ArenaId;
import com.mrfdev.walktheplank.config.ConfigurationManager;
import com.mrfdev.walktheplank.config.ConfigurationValidationReport;
import com.mrfdev.walktheplank.config.PermissionSettings;
import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.DatabaseDoctorReport;
import com.mrfdev.walktheplank.database.DurabilityMetrics;
import com.mrfdev.walktheplank.database.ScoreEntry;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.database.ScoreSnapshot;
import com.mrfdev.walktheplank.database.Season;
import com.mrfdev.walktheplank.database.RewardPlanRecord;
import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RewardStepRecord;
import com.mrfdev.walktheplank.database.RewardStepStatus;
import com.mrfdev.walktheplank.database.RunInvestigationQuery;
import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import com.mrfdev.walktheplank.database.RunStatus;
import com.mrfdev.walktheplank.export.LeaderboardExportService;
import com.mrfdev.walktheplank.game.Arena;
import com.mrfdev.walktheplank.game.GameManager;
import com.mrfdev.walktheplank.game.GameManager.QueueStatus;
import com.mrfdev.walktheplank.game.GameManager.SessionStatus;
import com.mrfdev.walktheplank.game.SessionEndReason;
import com.mrfdev.walktheplank.gui.MenuService;
import com.mrfdev.walktheplank.ops.OperationalContext;
import com.mrfdev.walktheplank.text.MessageService;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Permission-filtered player, administration, and diagnostics command surface. */
public final class WalkCommand implements CommandExecutor, TabCompleter {
    private static final String DOCS_URL = "https://github.com/mrfdev/WalkThePlank";
    private static final List<String> DEBUG_PAGES = List.of(
            "overview", "health", "hooks", "commands", "permissions", "placeholders", "config", "all");
    private static final DateTimeFormatter SNAPSHOT_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss z", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final Supplier<RuntimeSettings> settings;
    private final GameManager games;
    private final ScoreRepository scores;
    private final MenuService menus;
    private final MessageService messages;
    private final ReloadHandler reloadHandler;
    private final ConfigurationActivator configurationActivator;
    private final BuildInfo buildInfo;
    private final BooleanSupplier placeholderRegistered;
    private final ConfigurationManager configurationTools;
    private final ArenaConfigurationEditor arenaEditor;
    private final LeaderboardExportService exports;
    private final OperationalContext operations;
    private final AtomicBoolean doctorProbePending = new AtomicBoolean();
    private final AtomicBoolean configurationMutationPending = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<ArenaConfigurationEditor.CommittedEdit> committedArenaEdit =
            new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Boolean>> shutdownReconciliation =
            new AtomicReference<>();

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
            ConfigurationManager configurationTools,
            ConfigurationActivator configurationActivator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.games = Objects.requireNonNull(games, "games");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.reloadHandler = Objects.requireNonNull(reloadHandler, "reloadHandler");
        this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo");
        this.placeholderRegistered = Objects.requireNonNull(placeholderRegistered, "placeholderRegistered");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.configurationTools = Objects.requireNonNull(configurationTools, "configurationTools");
        this.configurationActivator = Objects.requireNonNull(
                configurationActivator, "configurationActivator");
        this.arenaEditor = new ArenaConfigurationEditor(plugin, configurationTools);
        this.exports = new LeaderboardExportService(plugin.getDataFolder().toPath());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (closing.get()) {
            sendLine(sender, "&cWalkThePlank is shutting down; no new command work is accepted.");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                openMenu(sender, player);
            } else {
                showHelp(sender);
            }
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "play" -> play(sender);
            case "leave" -> leave(sender);
            case "stats" -> stats(sender);
            case "top" -> showTop(sender, args);
            case "info", "version" -> showInfo(sender);
            case "help" -> showHelp(sender);
            case "queue" -> queue(sender, args);
            case "reload" -> reload(sender);
            case "open" -> openOther(sender, args);
            case "admin" -> admin(sender, args);
            case "debug" -> debug(sender, args.length >= 2 ? args[1] : "overview");
            default -> {
                Player target = plugin.getServer().getPlayerExact(args[0]);
                if (!(sender instanceof Player) && target != null) {
                    openOther(sender, new String[] {"open", target.getName()});
                } else {
                    messages.send(sender, "chat.wrongUsage");
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> roots = new ArrayList<>();
            PermissionSettings permissions = permissions();
            addIfAllowed(roots, sender, permissions.playGame(), "play");
            addIfAllowed(roots, sender, permissions.playGame(), "queue");
            if (sender instanceof Player player
                    && (games.isPlaying(player) || has(sender, permissions.leaveArena()))) {
                roots.add("leave");
            }
            addIfAllowed(roots, sender, permissions.stats(), "stats");
            addIfAllowed(roots, sender, permissions.top(), "top");
            addIfAllowed(roots, sender, permissions.info(), "info");
            addIfAllowed(roots, sender, permissions.help(), "help");
            addIfAllowed(roots, sender, permissions.reload(), "reload");
            addIfAllowed(roots, sender, permissions.adminOpen(), "open");
            if (hasAnyAdminPermission(sender)) {
                roots.add("admin");
            }
            addIfAllowed(roots, sender, permissions.adminDebug(), "debug");
            return complete(args[0], roots);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")
                && hasAdministrativePermission(sender, permissions().adminOpen())) {
            return complete(args[1], onlinePlayerNames());
        }
        if (args[0].equalsIgnoreCase("debug") && args.length == 2
                && hasAdministrativePermission(sender, permissions().adminDebug())) {
            return complete(args[1], DEBUG_PAGES);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("queue")
                && has(sender, permissions().playGame())) {
            return complete(args[1], List.of("join", "leave", "status", "ready"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")
                && has(sender, permissions().top())) {
            return complete(args[1], List.of("all-time", "season"));
        }
        if (!args[0].equalsIgnoreCase("admin")) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> choices = new ArrayList<>();
            choices.add("help");
            addAdminIfAllowed(choices, sender, permissions().adminOpen(), "open");
            addAdminIfAllowed(choices, sender, permissions().reload(), "reload");
            addAdminIfAllowed(choices, sender, permissions().adminStop(), "stop");
            addAdminIfAllowed(choices, sender, permissions().adminRecover(), "recover");
            addAdminIfAllowed(choices, sender, permissions().adminValidate(), "validate");
            addAdminIfAllowed(choices, sender, permissions().adminArena(), "arena");
            addAdminIfAllowed(choices, sender, permissions().adminQueue(), "queue");
            addAdminIfAllowed(choices, sender, permissions().adminSeason(), "season");
            addAdminIfAllowed(choices, sender, permissions().adminExport(), "export");
            addAdminIfAllowed(choices, sender, permissions().adminReward(), "reward");
            addAdminIfAllowed(choices, sender, permissions().adminInvestigate(), "run");
            addAdminIfAllowed(choices, sender, permissions().adminDebug(), "status");
            addAdminIfAllowed(choices, sender, permissions().adminDebug(), "debug");
            addAdminIfAllowed(choices, sender, permissions().adminDebug(), "doctor");
            return complete(args[1], choices);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("arena")
                && hasAdministrativePermission(sender, permissions().adminArena())) {
            return complete(args[2], List.of(
                    "list", "create", "setstart", "setexit", "clear-exit", "validate", "remove"));
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("queue")
                && hasAdministrativePermission(sender, permissions().adminQueue())) {
            return complete(args[2], List.of("status", "pause", "resume", "drain"));
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("season")
                && hasAdministrativePermission(sender, permissions().adminSeason())) {
            return complete(args[2], List.of(
                    "list", "create", "activate", "close", "reopen", "archive"));
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("export")
                && hasAdministrativePermission(sender, permissions().adminExport())) {
            return complete(args[2], List.of("all-time", "current-season", "season"));
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("reward")
                && hasAdministrativePermission(sender, permissions().adminReward())) {
            return complete(args[2], List.of("list", "inspect", "resolve", "abandon"));
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("run")
                && hasAdministrativePermission(sender, permissions().adminInvestigate())) {
            return complete(args[2], List.of("list", "inspect", "player", "arena", "season"));
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("run")
                && args[2].equalsIgnoreCase("list")
                && hasAdministrativePermission(sender, permissions().adminInvestigate())) {
            List<String> statuses = new ArrayList<>();
            statuses.add("all");
            statuses.addAll(java.util.Arrays.stream(RunStatus.values())
                    .map(status -> status.name().toLowerCase(Locale.ROOT))
                    .toList());
            return complete(args[3], statuses);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("run")
                && args[2].equalsIgnoreCase("arena")
                && hasAdministrativePermission(sender, permissions().adminInvestigate())) {
            return complete(args[3], configuredArenaIds());
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("run")
                && args[2].equalsIgnoreCase("season")
                && hasAdministrativePermission(sender, permissions().adminInvestigate())) {
            return complete(args[3], scores.seasons().stream()
                    .map(season -> season.id().toString())
                    .toList());
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("run")
                && args[2].equalsIgnoreCase("inspect")
                && hasAdministrativePermission(sender, permissions().adminInvestigate())) {
            return complete(args[3], scores.recentRuns(100).stream()
                    .map(run -> run.id().toString())
                    .toList());
        }
        if (args.length == 5 && args[1].equalsIgnoreCase("run")
                && List.of("list", "player", "arena", "season")
                        .contains(args[2].toLowerCase(Locale.ROOT))
                && hasAdministrativePermission(sender, permissions().adminInvestigate())) {
            return complete(args[4], List.of("10", "20", "50", "100"));
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("reward")
                && args[2].equalsIgnoreCase("list")
                && hasAdministrativePermission(sender, permissions().adminReward())) {
            return complete(args[3], java.util.Arrays.stream(RewardPlanStatus.values())
                    .map(status -> status.name().toLowerCase(Locale.ROOT))
                    .toList());
        }
        if (args.length == 6 && args[1].equalsIgnoreCase("reward")
                && args[2].equalsIgnoreCase("resolve")
                && hasAdministrativePermission(sender, permissions().adminReward())) {
            return complete(args[5], List.of("succeeded", "failed", "skipped"));
        }
        if (args.length == 7 && args[1].equalsIgnoreCase("reward")
                && args[2].equalsIgnoreCase("resolve")
                && hasAdministrativePermission(sender, permissions().adminReward())) {
            return complete(args[6], List.of("confirm"));
        }
        if (args.length == 5 && args[1].equalsIgnoreCase("reward")
                && args[2].equalsIgnoreCase("abandon")
                && hasAdministrativePermission(sender, permissions().adminReward())) {
            return complete(args[4], List.of("confirm"));
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("season")
                && hasAdministrativePermission(sender, permissions().adminSeason())
                && List.of("activate", "close", "reopen", "archive")
                        .contains(args[2].toLowerCase(Locale.ROOT))) {
            return complete(args[3], scores.seasons().stream()
                    .map(season -> season.id().toString())
                    .toList());
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("export")
                && args[2].equalsIgnoreCase("season")
                && hasAdministrativePermission(sender, permissions().adminExport())) {
            return complete(args[3], scores.seasons().stream()
                    .map(season -> season.id().toString())
                    .toList());
        }
        if (args.length == 3
                && args[1].equalsIgnoreCase("open")
                && hasAdministrativePermission(sender, permissions().adminOpen())) {
            return complete(args[2], onlinePlayerNames());
        }
        if (args.length == 3
                && args[1].equalsIgnoreCase("stop")
                && hasAdministrativePermission(sender, permissions().adminStop())) {
            return complete(args[2], onlinePlayerNames());
        }
        if (args.length == 3
                && args[1].equalsIgnoreCase("status")
                && hasAdministrativePermission(sender, permissions().adminDebug())) {
            return complete(args[2], onlinePlayerNames());
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("debug")
                && hasAdministrativePermission(sender, permissions().adminDebug())) {
            return complete(args[2], DEBUG_PAGES);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("arena")
                && hasAdministrativePermission(sender, permissions().adminArena())) {
            String action = args[2].toLowerCase(Locale.ROOT);
            if (List.of("setstart", "setexit", "clear-exit", "validate", "remove").contains(action)) {
                return complete(args[3], configuredArenaIds());
            }
        }
        if (args.length == 5
                && args[1].equalsIgnoreCase("arena")
                && args[2].equalsIgnoreCase("remove")
                && hasAdministrativePermission(sender, permissions().adminArena())) {
            return complete(args[4], List.of("confirm"));
        }
        return List.of();
    }

    private void play(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(sender, permissions().playGame())) {
            return;
        }
        games.start(player);
    }

    private void queue(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(sender, permissions().playGame())) {
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "join" -> games.joinQueue(player);
            case "leave" -> games.leaveQueue(player);
            case "ready" -> games.startReady(player);
            case "status" -> showQueueStatus(sender, games.queueStatus(player.getUniqueId()));
            default -> sendLine(sender, "&cUsage: /walk queue <join|leave|status|ready>");
        }
    }

    private void leave(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (!games.isPlaying(player) && !requirePermission(sender, permissions().leaveArena())) {
            return;
        }
        games.end(player, SessionEndReason.LEAVE);
    }

    private void stats(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(sender, permissions().stats())) {
            return;
        }
        menus.sendStats(player);
    }

    private void showTop(CommandSender sender, String[] args) {
        if (!requirePermission(sender, permissions().top())) {
            return;
        }
        if (args.length > 2) {
            sendLine(sender, "&cUsage: /walk top [all-time|season]");
            return;
        }
        boolean seasonRequested = args.length == 2 && args[1].equalsIgnoreCase("season");
        if (args.length == 2
                && !seasonRequested
                && !args[1].equalsIgnoreCase("all-time")) {
            sendLine(sender, "&cUsage: /walk top [all-time|season]");
            return;
        }
        ScoreSnapshot selected = seasonRequested
                ? scores.activeSeasonScores().orElse(null)
                : scores.snapshot();
        if (selected == null) {
            sendLine(sender, "&eThere is no active WalkThePlank season.");
            return;
        }
        if (seasonRequested) {
            sendLine(sender, "&7Season: &f{{seasonName}}", Map.of(
                    "seasonName",
                    scores.activeSeason().map(Season::name).orElse("active season")));
        }
        for (String line : messages.rawList("scoreboardRecordInChat.prefix")) {
            sendLine(sender, line);
        }
        String format = messages.raw(
                "scoreboardRecordInChat.record",
                "&f{{rank}}. &9{{playerName}} &7({{score}})");
        List<ScoreEntry> top = selected.top();
        if (top.isEmpty()) {
            sendLine(sender, "&7No scores have been recorded yet.");
        } else {
            int index = 1;
            for (ScoreEntry entry : top) {
                sendLine(sender, format, Map.of(
                        "index", index,
                        "rank", entry.rank(),
                        "playerName", entry.username(),
                        "score", entry.score()));
                index++;
            }
        }
        for (String line : messages.rawList("scoreboardRecordInChat.suffix")) {
            sendLine(sender, line);
        }
    }

    private void showInfo(CommandSender sender) {
        if (!requirePermission(sender, permissions().info())) {
            return;
        }
        sendHeader(sender, "Plugin information");
        sendField(sender, "Release", buildInfo.releaseLabel());
        sendField(sender, "Artifact", buildInfo.artifactFile());
        sendField(sender, "Source", buildInfo.sourceLabel());
        sendField(sender, "Platform", "Java " + buildInfo.javaTarget() + " / Paper " + buildInfo.paperTarget());
        sendField(sender, "Scores", Integer.toString(scores.snapshot().totalEntries()));
        sendField(sender, "Arenas", games.activeSessions() + " active, "
                + games.availableArenas() + " available, " + games.totalArenas() + " configured");
        sendField(sender, "Documentation", DOCS_URL);
    }

    private void showHelp(CommandSender sender) {
        if (!requirePermission(sender, permissions().help())) {
            return;
        }
        sendHeader(sender, "Command help");
        addHelp(sender, permissions().openGui(), "/walk", "Open the menu");
        addHelp(sender, permissions().playGame(), "/walk play", "Start a run");
        addHelp(sender, permissions().playGame(), "/walk queue <join|leave|status|ready>",
                "Reserve the next free arena fairly");
        if (sender instanceof Player player
                && (games.isPlaying(player) || has(sender, permissions().leaveArena()))) {
            sendCommandHelp(sender, "/walk leave", "End your run safely");
        }
        addHelp(sender, permissions().stats(), "/walk stats", "Show your personal best and rank");
        addHelp(sender, permissions().top(), "/walk top", "Show the top ten");
        addHelp(sender, permissions().info(), "/walk info", "Show build and platform information");
        if (hasAnyAdminPermission(sender)) {
            sendCommandHelp(sender, "/walk admin", "Show permission-filtered staff commands");
        }
        addAdminHelp(sender, permissions().adminDebug(), "/walk debug [page]", "Show safe diagnostics");
    }

    private void reload(CommandSender sender) {
        if (!requireAdministrativePermission(sender, permissions().reload())) {
            return;
        }
        if (!configurationMutationPending.compareAndSet(false, true)) {
            sendLine(sender, "&eA configuration mutation is already in progress.");
            return;
        }
        UUID operatorId = operatorId(sender);
        String actor = operatorKind(sender);
        sendLine(sender, "&7Reading and validating configuration asynchronously...");
        CompletableFuture<Boolean> reload;
        try {
            reload = reloadHandler.reload(() -> stillAuthorized(
                    sender, permissions().reload()));
        } catch (RuntimeException | LinkageError failure) {
            configurationMutationPending.set(false);
            plugin.getLogger().log(Level.SEVERE, "Could not start the configuration reload", failure);
            operations.audit("admin.reload", operatorId, null, Map.of(
                    "actor", actor,
                    "result", "start_failed"));
            messages.send(sender, "chat.reloadFailed");
            return;
        }
        reload.whenComplete((reloaded, failure) -> {
            configurationMutationPending.set(false);
            scheduleCommandReply(sender, () -> {
                boolean succeeded = failure == null && Boolean.TRUE.equals(reloaded);
                if (failure != null) {
                    plugin.getLogger().log(Level.SEVERE, "Could not complete the configuration reload", failure);
                }
                operations.audit("admin.reload", operatorId, null, Map.of(
                        "actor", actor,
                        "result", succeeded ? "reloaded" : "failed"));
                messages.send(sender, succeeded ? "chat.reloadSuccess" : "chat.reloadFailed");
            });
        });
    }

    private void openOther(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminOpen())) {
            return;
        }
        if (args.length != 2) {
            messages.send(sender, "chat.wrongUsage");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "chat.playerNotFound", Map.of("playerName", args[1]));
            return;
        }
        menus.open(target);
        operations.audit("admin.open", operatorId(sender), null, Map.of(
                "actor", operatorKind(sender),
                "target_player_id", target.getUniqueId(),
                "result", "opened"));
        sendLine(sender, "&aOpened the WalkThePlank menu for &f{{playerName}}&a.", Map.of(
                "playerName", target.getName()));
    }

    private void admin(CommandSender sender, String[] args) {
        if (!hasAnyAdminPermission(sender)) {
            deny(sender, permissions().admin());
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "help";
        switch (action) {
            case "help" -> showAdminHelp(sender);
            case "open" -> openOther(sender, shift(args));
            case "reload" -> reload(sender);
            case "stop" -> adminStop(sender, args);
            case "recover" -> adminRecover(sender);
            case "validate" -> adminValidate(sender, args);
            case "arena" -> adminArena(sender, args);
            case "queue" -> adminQueue(sender, args);
            case "season" -> adminSeason(sender, args);
            case "export" -> adminExport(sender, args);
            case "reward" -> adminReward(sender, args);
            case "run" -> adminRun(sender, args);
            case "status" -> adminStatus(sender, args);
            case "debug" -> debug(sender, args.length >= 3 ? args[2] : "overview");
            case "doctor" -> adminDoctor(sender, args);
            default -> showAdminHelp(sender);
        }
    }

    private void showAdminHelp(CommandSender sender) {
        sendHeader(sender, "Administration");
        addAdminHelp(sender, permissions().adminOpen(), "/walk admin open <player>", "Open a player's menu");
        addAdminHelp(sender, permissions().reload(), "/walk admin reload", "Reload and safely drain runs");
        addAdminHelp(sender, permissions().adminStop(), "/walk admin stop <player>", "Stop a run without rewards");
        addAdminHelp(
                sender,
                permissions().adminRecover(),
                "/walk admin recover",
                "Retry quarantined blocks and eligible player recovery");
        addAdminHelp(sender, permissions().adminValidate(), "/walk admin validate", "Validate config without applying it");
        addAdminHelp(sender, permissions().adminArena(), "/walk admin arena", "Safely manage configured arenas");
        addAdminHelp(sender, permissions().adminQueue(), "/walk admin queue", "Inspect, pause, or drain the queue");
        addAdminHelp(sender, permissions().adminSeason(), "/walk admin season", "Manage explicit event seasons");
        addAdminHelp(sender, permissions().adminExport(), "/walk admin export", "Write UUID-only CSV/JSON snapshots");
        addAdminHelp(sender, permissions().adminReward(), "/walk admin reward", "Inspect/resolve UNKNOWN rewards without replay");
        addAdminHelp(sender, permissions().adminInvestigate(), "/walk admin run", "Query redacted retained-run evidence");
        addAdminHelp(sender, permissions().adminDebug(), "/walk admin status [player]", "Show arena or run status");
        addAdminHelp(sender, permissions().adminDebug(), "/walk admin debug [page]", "Show safe diagnostics");
        addAdminHelp(
                sender,
                permissions().adminDebug(),
                "/walk admin doctor",
                "Create a privacy-safe asynchronous support report");
    }

    private void adminStop(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminStop())) {
            return;
        }
        if (args.length != 3) {
            sendLine(sender, "&cUsage: /walk admin stop <player>");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            messages.send(sender, "chat.playerNotFound", Map.of("playerName", args[2]));
            return;
        }
        if (!games.end(target, SessionEndReason.ADMIN)) {
            operations.audit("admin.stop", operatorId(sender), null, Map.of(
                    "actor", operatorKind(sender),
                    "target_player_id", target.getUniqueId(),
                    "result", "not_active"));
            sendLine(sender, "&e{{playerName}} is not in a WalkThePlank run.", Map.of(
                    "playerName", target.getName()));
            return;
        }
        operations.audit("admin.stop", operatorId(sender), null, Map.of(
                "actor", operatorKind(sender),
                "target_player_id", target.getUniqueId(),
                "result", "stopped"));
        sendLine(sender, "&aStopped &f{{playerName}}&a's run without rewards.", Map.of(
                "playerName", target.getName()));
    }

    private void adminRecover(CommandSender sender) {
        if (!requireAdministrativePermission(sender, permissions().adminRecover())) {
            return;
        }
        completeOnMainThread(
                games.retryQuarantinedArenas(operatorId(sender)),
                recovered -> sendLine(
                        sender,
                        "&aRestoration retry complete: &f{{recovered}}&a arena(s) recovered, "
                                + "&f{{quarantined}}&a still quarantined.",
                        Map.of(
                                "recovered", recovered,
                                "quarantined", games.quarantinedArenas())),
                "arena restoration retry",
                sender);
    }

    private void adminQueue(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminQueue())) {
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status" -> showQueueStatus(sender, games.queueStatus(new java.util.UUID(0L, 0L)));
            case "pause" -> {
                games.setQueuePaused(true, operatorId(sender));
                sendLine(sender, "&aThe arena queue is paused; existing positions are preserved.");
            }
            case "resume" -> {
                games.setQueuePaused(false, operatorId(sender));
                sendLine(sender, "&aThe arena queue is accepting readiness assignments again.");
            }
            case "drain" -> sendLine(
                    sender,
                    "&aRemoved &f{{players}}&a player(s) from the arena queue.",
                    Map.of("players", games.drainQueue(operatorId(sender))));
            default -> sendLine(sender, "&cUsage: /walk admin queue <status|pause|resume|drain>");
        }
    }

    private void showQueueStatus(CommandSender sender, QueueStatus status) {
        sendHeader(sender, "Arena queue");
        sendField(sender, "Enabled", Boolean.toString(status.enabled()));
        sendField(sender, "State", status.paused() ? "paused" : "running");
        sendField(sender, "Players", status.total() + " total / "
                + status.waiting() + " waiting / " + status.ready() + " ready");
        if (status.playerPosition() > 0) {
            sendField(sender, "Your position", Integer.toString(status.playerPosition()));
        }
        status.playerReadyUntil().ifPresent(readyUntil ->
                sendField(sender, "Reserved until", SNAPSHOT_TIME.format(readyUntil)));
    }

    private void adminSeason(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminSeason())) {
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> showSeasons(sender, args);
            case "create" -> createSeason(sender, args);
            case "activate" -> transitionSeason(sender, args, "activated", scores::activateSeason);
            case "close" -> transitionSeason(sender, args, "closed", scores::closeSeason);
            case "reopen" -> transitionSeason(sender, args, "reopened as planned", scores::reopenSeason);
            case "archive" -> transitionSeason(sender, args, "archived", scores::archiveSeason);
            default -> sendLine(sender, "&cUsage: /walk admin season "
                    + "<list|create <name>|activate <uuid>|close <uuid>|reopen <uuid>|archive <uuid>>");
        }
    }

    private void showSeasons(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sendLine(sender, "&cUsage: /walk admin season list");
            return;
        }
        sendHeader(sender, "Event seasons");
        List<Season> seasons = scores.seasons();
        if (seasons.isEmpty()) {
            sendLine(sender, "&7No seasons have been created. All-time scores remain active.");
            return;
        }
        for (Season season : seasons) {
            sendLine(
                    sender,
                    "&3{{seasonId}} &7- &f{{seasonName}} &7({{status}}, changed {{changedAt}})",
                    Map.of(
                            "seasonId", season.id(),
                            "seasonName", season.name(),
                            "status", season.status(),
                            "changedAt", SNAPSHOT_TIME.format(season.transitionedAt())));
        }
    }

    private void createSeason(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sendLine(sender, "&cUsage: /walk admin season create <name>");
            return;
        }
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).strip();
        if (name.isEmpty() || name.length() > 80) {
            sendLine(sender, "&cSeason names must contain between 1 and 80 characters.");
            return;
        }
        UUID id = UUID.randomUUID();
        reportSeasonMutation(sender, "created", scores.createSeason(id, name, Instant.now()));
    }

    private void transitionSeason(
            CommandSender sender,
            String[] args,
            String verb,
            SeasonTransition transition) {
        if (args.length != 4) {
            sendLine(sender, "&cUsage: /walk admin season {{action}} <uuid>", Map.of(
                    "action", args[2]));
            return;
        }
        UUID id = parseUuid(sender, args[3], "Season");
        if (id == null) {
            return;
        }
        reportSeasonMutation(sender, verb, transition.apply(id, Instant.now()));
    }

    private void reportSeasonMutation(
            CommandSender sender,
            String verb,
            CompletableFuture<Season> mutation) {
        completeOnMainThread(
                mutation,
                season -> {
                    games.auditSeasonTransition(operatorId(sender), season);
                    sendLine(
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

    private void adminExport(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminExport())) {
            return;
        }
        if (args.length < 3 || args.length > 4) {
            sendLine(sender, "&cUsage: /walk admin export <all-time|current-season|season <uuid>>");
            return;
        }
        String category = args[2].toLowerCase(Locale.ROOT);
        switch (category) {
            case "all-time" -> exportSnapshot(sender, scores.snapshot(), "all-time");
            case "current-season" -> {
                ScoreSnapshot current = scores.activeSeasonScores().orElse(null);
                if (current == null) {
                    sendLine(sender, "&eThere is no active season to export.");
                    return;
                }
                exportSnapshot(sender, current, "current-season");
            }
            case "season" -> exportHistoricalSeason(sender, args);
            default -> sendLine(sender,
                    "&cUsage: /walk admin export <all-time|current-season|season <uuid>>");
        }
    }

    private void exportHistoricalSeason(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sendLine(sender, "&cUsage: /walk admin export season <uuid>");
            return;
        }
        UUID id = parseUuid(sender, args[3], "Season");
        if (id == null) {
            return;
        }
        completeOnMainThread(
                scores.seasonScores(id),
                snapshot -> exportSnapshot(sender, snapshot, "season-" + id),
                "season export lookup",
                sender);
    }

    private void exportSnapshot(CommandSender sender, ScoreSnapshot snapshot, String category) {
        sendLine(sender, "&7Writing a UUID-only leaderboard snapshot...");
        UUID requestingOperator = operatorId(sender);
        operations.submitRequired(() -> {
                    if (!awaitMainApproval(() -> stillAuthorized(
                            sender, permissions().adminExport()))) {
                        throw new SecurityException(
                                "Leaderboard export authorization changed before execution");
                    }
                    return exports.export(snapshot.scores(), category, snapshot.capturedAt());
                })
                .whenComplete((result, failure) -> {
            if (failure == null) {
                games.auditLeaderboardExport(requestingOperator, category, result.rows());
                scheduleCommandReply(sender, () -> {
                    sendLine(sender, "&aExported &f{{rows}}&a row(s).", Map.of(
                            "rows", result.rows()));
                    sendField(sender, "CSV", result.csvFileName());
                    sendField(sender, "JSON", result.jsonFileName());
                });
                return;
            }
            plugin.getLogger().log(Level.SEVERE, "Could not export the leaderboard", failure);
            scheduleCommandReply(sender, () -> sendLine(
                    sender,
                    "&cThe UUID-only export failed safely; see the console for its error category."));
        });
    }

    private void adminRun(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminInvestigate())) {
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "help";
        switch (action) {
            case "help" -> showRunInvestigationHelp(sender);
            case "list" -> listInvestigatedRuns(sender, args);
            case "inspect" -> inspectRun(sender, args);
            case "player" -> investigatePlayerRuns(sender, args);
            case "arena" -> investigateArenaRuns(sender, args);
            case "season" -> investigateSeasonRuns(sender, args);
            default -> showRunInvestigationHelp(sender);
        }
    }

    private void showRunInvestigationHelp(CommandSender sender) {
        sendHeader(sender, "Retained-run investigation");
        sendCommandHelp(sender, "/walk admin run list [status|all] [limit]",
                "List newest retained UUID-owned run evidence");
        sendCommandHelp(sender, "/walk admin run inspect <run-uuid>",
                "Inspect one exact retained run");
        sendCommandHelp(sender, "/walk admin run player <player-uuid> [limit]",
                "Filter by immutable player UUID");
        sendCommandHelp(sender, "/walk admin run arena <arena-id> [limit]",
                "Filter by exact arena ID");
        sendCommandHelp(sender, "/walk admin run season <season-uuid> [limit]",
                "Filter by exact season UUID");
        sendLine(sender, "&7Limits are 1-100 (default 20). Names are never ownership filters.");
    }

    private void listInvestigatedRuns(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 5) {
            sendLine(sender, "&cUsage: /walk admin run list [status|all] [limit]");
            return;
        }
        Optional<RunStatus> status = Optional.empty();
        if (args.length >= 4 && !args[3].equalsIgnoreCase("all")) {
            try {
                status = Optional.of(RunStatus.valueOf(args[3].toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException invalidStatus) {
                sendLine(sender, "&cUnknown run status. Use: all, {{statuses}}", Map.of(
                        "statuses",
                        String.join(", ", java.util.Arrays.stream(RunStatus.values())
                                .map(value -> value.name().toLowerCase(Locale.ROOT))
                                .toList())));
                return;
            }
        }
        Integer limit = parseInvestigationLimit(sender, args, 4);
        if (limit == null) {
            return;
        }
        RunInvestigationQuery query = status
                .map(value -> RunInvestigationQuery.forStatus(value, limit))
                .orElseGet(() -> RunInvestigationQuery.all(limit));
        String filter = status.map(value -> "status:" + value).orElse("all");
        listInvestigatedRuns(sender, query, filter);
    }

    private void inspectRun(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sendLine(sender, "&cUsage: /walk admin run inspect <run-uuid>");
            return;
        }
        UUID runId = parseUuid(sender, args[3], "Run");
        if (runId == null) {
            return;
        }
        completeOnMainThread(
                scores.run(runId),
                retained -> {
                    auditRunInvestigation(sender, "run:" + runId, retained.isPresent() ? 1 : 0);
                    if (retained.isEmpty()) {
                        sendLine(sender, "&eNo retained run has that UUID.");
                        return;
                    }
                    showInvestigatedRun(sender, retained.orElseThrow());
                },
                "retained-run inspection",
                sender);
    }

    private void investigatePlayerRuns(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 5) {
            sendLine(sender, "&cUsage: /walk admin run player <player-uuid> [limit]");
            return;
        }
        UUID playerId = parseUuid(sender, args[3], "Player");
        if (playerId == null) {
            return;
        }
        Integer limit = parseInvestigationLimit(sender, args, 4);
        if (limit == null) {
            return;
        }
        listInvestigatedRuns(
                sender,
                RunInvestigationQuery.forPlayer(playerId, limit),
                "player:" + playerId);
    }

    private void investigateArenaRuns(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 5) {
            sendLine(sender, "&cUsage: /walk admin run arena <arena-id> [limit]");
            return;
        }
        Integer limit = parseInvestigationLimit(sender, args, 4);
        if (limit == null) {
            return;
        }
        try {
            listInvestigatedRuns(
                    sender,
                    RunInvestigationQuery.forArena(args[3], limit),
                    "arena:" + safeEvidenceText(args[3], 128));
        } catch (IllegalArgumentException invalidArena) {
            sendLine(sender, "&cArena IDs must be nonblank, contain no control characters, and use at most 128 characters.");
        }
    }

    private void investigateSeasonRuns(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 5) {
            sendLine(sender, "&cUsage: /walk admin run season <season-uuid> [limit]");
            return;
        }
        UUID seasonId = parseUuid(sender, args[3], "Season");
        if (seasonId == null) {
            return;
        }
        Integer limit = parseInvestigationLimit(sender, args, 4);
        if (limit == null) {
            return;
        }
        listInvestigatedRuns(
                sender,
                RunInvestigationQuery.forSeason(seasonId, limit),
                "season:" + seasonId);
    }

    private void listInvestigatedRuns(
            CommandSender sender,
            RunInvestigationQuery query,
            String filter) {
        completeOnMainThread(
                scores.investigateRuns(query),
                runs -> {
                    auditRunInvestigation(sender, filter, runs.size());
                    sendHeader(sender, "Retained runs");
                    sendField(sender, "Filter", safeEvidenceText(filter, 160));
                    sendField(sender, "Results", Integer.toString(runs.size()));
                    if (runs.isEmpty()) {
                        sendLine(sender, "&7No retained runs match that exact filter.");
                        return;
                    }
                    for (RunInvestigationRecord run : runs) {
                        String plan = run.rewardPlanId()
                                .map(id -> id + "/" + run.rewardPlanStatus().orElseThrow())
                                .orElse("none");
                        sendLine(
                                sender,
                                "&3{{runId}} &7| &f{{status}} &7| player &f{{playerId}} "
                                        + "&7| arena &f{{arenaId}} &7| started &f{{startedAt}} "
                                        + "&7| score &f{{score}} &7| plan &f{{plan}}",
                                Map.of(
                                        "runId", run.runId(),
                                        "status", run.status(),
                                        "playerId", run.playerId(),
                                        "arenaId", safeEvidenceText(run.arenaId(), 128),
                                        "startedAt", SNAPSHOT_TIME.format(run.startedAt()),
                                        "score", run.score().map(String::valueOf).orElse("-"),
                                        "plan", plan));
                    }
                },
                "retained-run query",
                sender);
    }

    private void showInvestigatedRun(CommandSender sender, RunInvestigationRecord run) {
        sendHeader(sender, "Retained run " + run.runId());
        sendField(sender, "Player UUID", run.playerId().toString());
        sendField(sender, "Status", run.status().name());
        sendField(sender, "Arena", safeEvidenceText(run.arenaId(), 128));
        sendField(sender, "Started", SNAPSHOT_TIME.format(run.startedAt()));
        sendField(sender, "Ended", run.endedAt().map(SNAPSHOT_TIME::format).orElse("not recorded"));
        sendField(sender, "Score", run.score().map(String::valueOf).orElse("not projected"));
        sendField(sender, "End reason", run.endReason()
                .map(value -> safeEvidenceText(value, 64))
                .orElse("not recorded"));
        sendField(sender, "Release", safeEvidenceText(run.release(), 128));
        sendField(sender, "Season UUID", run.seasonId().map(UUID::toString).orElse("none"));
        sendField(sender, "Reward plan", run.rewardPlanId().map(UUID::toString).orElse("none"));
        sendField(sender, "Reward status", run.rewardPlanStatus()
                .map(RewardPlanStatus::name)
                .orElse("none"));
    }

    private Integer parseInvestigationLimit(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            return 20;
        }
        try {
            int limit = Integer.parseInt(args[index]);
            if (limit < 1 || limit > RunInvestigationQuery.MAXIMUM_RESULTS) {
                throw new NumberFormatException("out of range");
            }
            return limit;
        } catch (NumberFormatException invalidLimit) {
            sendLine(sender, "&cRun investigation limit must be a number from 1 through 100.");
            return null;
        }
    }

    private void auditRunInvestigation(CommandSender sender, String filter, int results) {
        operations.audit("run.investigation", operatorId(sender), null, Map.of(
                "filter", safeEvidenceText(filter, 160),
                "results", results));
    }

    private static String safeEvidenceText(String value, int maximumLength) {
        String safe = Objects.requireNonNull(value, "value")
                .replace('&', '_')
                .replace('\u00a7', '_')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .strip();
        if (safe.startsWith("/")
                || safe.startsWith("\\")
                || safe.startsWith("~/")
                || safe.matches("^[A-Za-z]:[\\\\/].*")
                || safe.matches(".*\\s/\\S+.*")
                || safe.matches(".*\\s[A-Za-z]:[\\\\/]\\S+.*")
                || safe.contains(" \\\\")) {
            return "[redacted absolute path]";
        }
        return safe.length() <= maximumLength
                ? safe
                : safe.substring(0, maximumLength - 1) + "\u2026";
    }

    private void adminReward(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminReward())) {
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> listRewardPlans(sender, args);
            case "inspect" -> inspectRewardPlan(sender, args);
            case "resolve" -> resolveRewardStep(sender, args);
            case "abandon" -> abandonRewardPlan(sender, args);
            default -> showRewardHelp(sender);
        }
    }

    private void showRewardHelp(CommandSender sender) {
        sendHeader(sender, "Durable rewards");
        sendCommandHelp(sender, "/walk admin reward list [status] [limit]",
                "List redacted plans, newest first");
        sendCommandHelp(sender, "/walk admin reward inspect <plan-uuid>",
                "Show roots, hashes, and persisted outcomes only");
        sendCommandHelp(sender,
                "/walk admin reward resolve <plan-uuid> <step> <succeeded|failed|skipped> confirm",
                "Resolve UNKNOWN evidence without replaying a command");
        sendCommandHelp(sender, "/walk admin reward abandon <plan-uuid> confirm",
                "Freeze every remaining PENDING step without replay");
    }

    private void listRewardPlans(CommandSender sender, String[] args) {
        if (args.length > 5) {
            sendLine(sender, "&cUsage: /walk admin reward list [status] [limit]");
            return;
        }
        RewardPlanStatus status = RewardPlanStatus.UNKNOWN;
        if (args.length >= 4) {
            try {
                status = RewardPlanStatus.valueOf(args[3].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalidStatus) {
                sendLine(sender, "&cUnknown reward status. Use: {{statuses}}", Map.of(
                        "statuses",
                        String.join(", ", java.util.Arrays.stream(RewardPlanStatus.values())
                                .map(value -> value.name().toLowerCase(Locale.ROOT))
                                .toList())));
                return;
            }
        }
        int limit = 20;
        if (args.length == 5) {
            try {
                limit = Integer.parseInt(args[4]);
            } catch (NumberFormatException invalidLimit) {
                sendLine(sender, "&cReward list limit must be a number from 1 through 100.");
                return;
            }
        }
        if (limit < 1 || limit > 100) {
            sendLine(sender, "&cReward list limit must be from 1 through 100.");
            return;
        }
        RewardPlanStatus requestedStatus = status;
        completeOnMainThread(
                scores.recentRewardPlans(status, limit),
                plans -> {
                    sendHeader(sender, "Reward plans: " + requestedStatus);
                    if (plans.isEmpty()) {
                        sendLine(sender, "&7No plans have this status.");
                        return;
                    }
                    for (RewardPlanRecord plan : plans) {
                        sendLine(
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

    private void inspectRewardPlan(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sendLine(sender, "&cUsage: /walk admin reward inspect <plan-uuid>");
            return;
        }
        UUID planId = parseUuid(sender, args[3], "Reward plan");
        if (planId == null) {
            return;
        }
        completeOnMainThread(
                scores.rewardPlan(planId),
                plan -> {
                    if (plan.isEmpty()) {
                        sendLine(sender, "&eNo retained reward plan has that ID.");
                        return;
                    }
                    showRewardPlan(sender, plan.orElseThrow());
                },
                "reward-plan inspection",
                sender);
    }

    private void resolveRewardStep(CommandSender sender, String[] args) {
        if (args.length != 7 || !args[6].equalsIgnoreCase("confirm")) {
            sendLine(sender, "&cUsage: /walk admin reward resolve <plan-uuid> <step> "
                    + "<succeeded|failed|skipped> confirm");
            sendLine(sender, "&eThis records staff's conclusion and never replays the command.");
            return;
        }
        UUID planId = parseUuid(sender, args[3], "Reward plan");
        if (planId == null) {
            return;
        }
        int stepIndex;
        try {
            stepIndex = Integer.parseInt(args[4]);
        } catch (NumberFormatException invalidStep) {
            sendLine(sender, "&cReward step index must be a non-negative number.");
            return;
        }
        if (stepIndex < 0) {
            sendLine(sender, "&cReward step index must be a non-negative number.");
            return;
        }
        RewardStepStatus resolution;
        try {
            resolution = RewardStepStatus.valueOf(args[5].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidResolution) {
            sendLine(sender, "&cResolution must be succeeded, failed, or skipped.");
            return;
        }
        if (!List.of(
                        RewardStepStatus.SUCCEEDED,
                        RewardStepStatus.FAILED,
                        RewardStepStatus.SKIPPED)
                .contains(resolution)) {
            sendLine(sender, "&cResolution must be succeeded, failed, or skipped.");
            return;
        }
        int resolvedStep = stepIndex;
        RewardStepStatus resolvedStatus = resolution;
        completeOnMainThread(
                scores.resolveUnknownRewardStep(planId, stepIndex, resolution, Instant.now()),
                plan -> {
                    games.auditRewardResolution(
                            operatorId(sender), planId, resolvedStep, resolvedStatus);
                    sendLine(
                            sender,
                            "&aResolved UNKNOWN reward step &f{{step}} &aas &f{{resolution}}"
                                    + "&a; plan is now &f{{status}}&a.",
                            Map.of(
                                    "step", resolvedStep,
                                    "resolution", resolvedStatus,
                                    "status", plan.status()));
                },
                "UNKNOWN reward resolution",
                sender);
    }

    private void abandonRewardPlan(CommandSender sender, String[] args) {
        if (args.length != 5 || !args[4].equalsIgnoreCase("confirm")) {
            sendLine(sender, "&cUsage: /walk admin reward abandon <plan-uuid> confirm");
            sendLine(sender, "&eThis permanently skips every remaining command and never replays it.");
            return;
        }
        UUID planId = parseUuid(sender, args[3], "Reward plan");
        if (planId == null) {
            return;
        }
        completeOnMainThread(
                scores.finalizeRewardPlan(planId, Instant.now()),
                plan -> {
                    games.auditRewardAbandon(operatorId(sender), planId, plan.status());
                    sendLine(
                            sender,
                            "&aReward plan &f{{planId}} &ais now &f{{status}}"
                                    + "&a. No command was replayed.",
                            Map.of("planId", planId, "status", plan.status()));
                },
                "reward-plan abandonment",
                sender);
    }

    private void showRewardPlan(CommandSender sender, RewardPlanRecord plan) {
        sendHeader(sender, "Reward plan " + plan.planId());
        sendField(sender, "Run", plan.runId().toString());
        sendField(sender, "Status", plan.status().name());
        sendField(sender, "Created", SNAPSHOT_TIME.format(plan.createdAt()));
        for (RewardStepRecord step : plan.steps()) {
            sendLine(
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

    private UUID parseUuid(CommandSender sender, String value, String description) {
        try {
            UUID parsed = UUID.fromString(value);
            if (value.length() != 36 || !parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException invalidId) {
            sendLine(sender, "&c{{description}} IDs must be complete UUIDs.", Map.of(
                    "description", description));
            return null;
        }
    }

    private <T> void completeOnMainThread(
            CompletableFuture<T> future,
            Consumer<T> success,
            String operation,
            CommandSender sender) {
        future.whenComplete((value, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Could not complete " + operation, failure);
                scheduleCommandReply(sender, () -> sendLine(
                        sender,
                        "&cThe {{operation}} operation was rejected; no unsafe fallback was applied.",
                        Map.of("operation", operation)));
                return;
            }
            scheduleCommandReply(sender, () -> success.accept(value));
        });
    }

    private void scheduleCommandReply(CommandSender sender, Runnable reply) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, reply);
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not schedule a WalkThePlank command reply",
                    schedulingFailure);
        }
    }

    /**
     * Stops new command work and queues a FIFO reconciliation barrier. Any arena edit that reached
     * durable commit but not final verified activation is restored from its exact in-memory token
     * before the operations worker closes.
     */
    public CompletableFuture<Boolean> prepareShutdown() {
        closing.set(true);
        CompletableFuture<Boolean> existing = shutdownReconciliation.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Boolean> reconciliation = operations.submitRequired(() -> {
            ArenaConfigurationEditor.CommittedEdit committed = committedArenaEdit.get();
            if (committed == null) {
                configurationMutationPending.set(false);
                return true;
            }
            try {
                arenaEditor.rollback(committed);
                committedArenaEdit.compareAndSet(committed, null);
                configurationMutationPending.set(false);
                return true;
            } catch (IOException rollbackFailure) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not reconcile a committed arena edit during shutdown",
                        rollbackFailure);
                return false;
            }
        });
        if (shutdownReconciliation.compareAndSet(null, reconciliation)) {
            return reconciliation;
        }
        return shutdownReconciliation.get();
    }

    /**
     * Worker-side handshake that evaluates the live predicate on the primary thread immediately
     * before the queued operation continues.
     */
    private boolean awaitMainApproval(BooleanSupplier approval) throws Exception {
        Objects.requireNonNull(approval, "approval");
        if (closing.get()) {
            return false;
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    result.complete(approval.getAsBoolean());
                } catch (RuntimeException | LinkageError failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            result.completeExceptionally(schedulingFailure);
        }
        return result.get(2L, TimeUnit.SECONDS);
    }

    private void adminValidate(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminValidate())) {
            return;
        }
        if (args.length != 2) {
            sendLine(sender, "&cUsage: /walk admin validate");
            return;
        }
        validateCapturedConfiguration(
                sender,
                "configuration",
                null,
                permissions().adminValidate(),
                "Configuration validation");
    }

    private void adminArena(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminArena())) {
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "help";
        switch (action) {
            case "help" -> showArenaHelp(sender);
            case "list" -> adminArenaList(sender, args);
            case "create" -> adminArenaLocationEdit(sender, args, ArenaLocationEdit.CREATE);
            case "setstart" -> adminArenaLocationEdit(sender, args, ArenaLocationEdit.SET_START);
            case "setexit" -> adminArenaLocationEdit(sender, args, ArenaLocationEdit.SET_EXIT);
            case "clear-exit" -> adminArenaClearExit(sender, args);
            case "validate" -> adminArenaValidate(sender, args);
            case "remove" -> adminArenaRemove(sender, args);
            default -> showArenaHelp(sender);
        }
    }

    private void showArenaHelp(CommandSender sender) {
        sendHeader(sender, "Arena editor");
        sendCommandHelp(sender, "/walk admin arena list", "List configured arenas");
        sendCommandHelp(sender, "/walk admin arena create <id>", "Create an arena at your block");
        sendCommandHelp(sender, "/walk admin arena setstart <id>", "Capture your block as its start");
        sendCommandHelp(sender, "/walk admin arena setexit <id>", "Capture your location as its exit");
        sendCommandHelp(sender, "/walk admin arena clear-exit <id>", "Return players to their saved location");
        sendCommandHelp(sender, "/walk admin arena validate [id]", "Validate without changing anything");
        sendCommandHelp(sender, "/walk admin arena remove <id> confirm", "Remove with explicit confirmation");
    }

    private void adminArenaList(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sendLine(sender, "&cUsage: /walk admin arena list");
            return;
        }
        List<Arena> arenas = settings.get().arenas();
        sendHeader(sender, "Active configured arenas");
        for (Arena arena : arenas) {
            org.bukkit.Location start = arena.start();
            sendLine(
                    sender,
                    "&3{{arenaId}} &7- &f{{world}} @ {{position}} &7({{exitMode}})",
                    Map.of(
                            "arenaId", arena.id(),
                            "world", Objects.requireNonNull(start.getWorld(), "arena world").getName(),
                            "position", start.getBlockX() + ","
                                    + start.getBlockY() + "," + start.getBlockZ(),
                            "exitMode", arena.exit() != null ? "custom exit" : "saved return"));
        }
        sendField(sender, "Total", Integer.toString(arenas.size()));
    }

    private void adminArenaLocationEdit(
            CommandSender sender,
            String[] args,
            ArenaLocationEdit edit) {
        if (args.length != 4) {
            sendLine(sender, "&cUsage: /walk admin arena {{action}} <id>", Map.of(
                    "action", edit.command()));
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null || !requireArenaEditingIdle(sender)) {
            return;
        }
        String id = args[3];
        ArenaConfigurationEditor.LocationData location =
                ArenaConfigurationEditor.LocationData.capture(player.getLocation());
        applyArenaEdit(
                sender,
                id,
                edit.command(),
                edit.successVerb() + " arena '" + id + "'",
                (files, generation) -> switch (edit) {
                    case CREATE -> arenaEditor.prepareCreate(files, generation, id, location);
                    case SET_START -> arenaEditor.prepareSetStart(files, generation, id, location);
                    case SET_EXIT -> arenaEditor.prepareSetExit(files, generation, id, location);
                });
    }

    private void adminArenaClearExit(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sendLine(sender, "&cUsage: /walk admin arena clear-exit <id>");
            return;
        }
        if (!requireArenaEditingIdle(sender)) {
            return;
        }
        String id = args[3];
        applyArenaEdit(sender, id, "clear-exit", "Cleared the custom exit for arena '" + id + "'",
                (files, generation) -> arenaEditor.prepareClearExit(files, generation, id));
    }

    private void adminArenaValidate(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            sendLine(sender, "&cUsage: /walk admin arena validate [id]");
            return;
        }
        String arenaId = null;
        if (args.length == 4) {
            try {
                arenaId = ArenaId.requireValid(args[3]);
            } catch (IllegalArgumentException exception) {
                sendLine(sender, "&c{{error}}", Map.of(
                        "error", String.valueOf(exception.getMessage())));
                return;
            }
        }
        validateCapturedConfiguration(
                sender,
                "arena",
                arenaId,
                permissions().adminArena(),
                "Arena configuration validation");
    }

    private void adminArenaRemove(CommandSender sender, String[] args) {
        if (args.length != 5 || !args[4].equalsIgnoreCase("confirm")) {
            sendLine(sender, "&cUsage: /walk admin arena remove <id> confirm");
            sendLine(sender, "&eRemoval is destructive and always requires the literal confirmation word.");
            return;
        }
        if (!requireArenaEditingIdle(sender)) {
            return;
        }
        String id = args[3];
        applyArenaEdit(
                sender,
                id,
                "remove",
                "Removed arena '" + id + "'",
                (files, generation) -> arenaEditor.prepareRemove(files, generation, id));
    }

    private boolean requireArenaEditingIdle(CommandSender sender) {
        if (!games.isConfigurationMutationIdle()) {
            sendLine(
                    sender,
                    "&cArena configuration requires an empty queue and no active, pending, "
                            + "quarantined, or recovery-owned arena work.");
            return false;
        }
        return true;
    }

    private boolean isArenaEditingIdle() {
        return games.isConfigurationMutationIdle();
    }

    private void validateCapturedConfiguration(
            CommandSender sender,
            String scope,
            String arenaId,
            String permission,
            String title) {
        sendLine(sender, "&7Reading configuration asynchronously for validation...");
        operations.submitRequired(configurationTools::captureFiles)
                .whenComplete((files, failure) -> scheduleCommandReply(sender, () -> {
                    if (failure != null) {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Could not capture configuration for validation",
                                failure);
                        sendLine(sender, "&cThe configuration files could not be read safely.");
                        return;
                    }
                    if (!stillAuthorized(sender, permission)) {
                        sendLine(sender, "&cAuthorization changed before validation completed.");
                        return;
                    }
                    if (arenaId != null) {
                        try {
                            if (!arenaEditor.contains(files, arenaId)) {
                                sendLine(sender, "&cArena '{{arenaId}}' does not exist.", Map.of(
                                        "arenaId", arenaId));
                                return;
                            }
                            sendLine(
                                    sender,
                                    "&7Checking arena &f{{arenaId}}&7 within the complete arena layout.",
                                    Map.of("arenaId", arenaId));
                        } catch (IOException | IllegalArgumentException exception) {
                            plugin.getLogger().log(
                                    Level.WARNING,
                                    "Could not parse an arena for validation",
                                    exception);
                            sendLine(sender, "&cThe captured arena list could not be parsed safely.");
                            return;
                        }
                    }
                    try {
                        ConfigurationValidationReport report = configurationTools.validate(files);
                        if (!report.valid()) {
                            auditValidation(sender, scope, arenaId, report);
                            sendValidationReport(sender, title, report);
                            return;
                        }
                        ConfigurationManager.ConfigurationSnapshot candidate =
                                configurationTools.prepare(files);
                        operations.submitRequired(() -> {
                            configurationTools.requireFilesUnchanged(files);
                            configurationTools.requireDatabaseStorageSafe(
                                    candidate.databaseSettings());
                            return null;
                        }).whenComplete((ignored, storageFailure) ->
                                scheduleCommandReply(sender, () -> {
                                    if (!stillAuthorized(sender, permission)) {
                                        sendLine(
                                                sender,
                                                "&cAuthorization changed before validation completed.");
                                        return;
                                    }
                                    ConfigurationValidationReport completed = report;
                                    if (storageFailure != null) {
                                        plugin.getLogger().log(
                                                Level.WARNING,
                                                "Configuration snapshot or SQLite storage failed final validation",
                                                storageFailure);
                                        List<String> errors = new ArrayList<>(report.errors());
                                        errors.add(
                                                "Configuration changed during validation or SQLite storage "
                                                        + "is not a safe usable target");
                                        completed = new ConfigurationValidationReport(
                                                errors, report.warnings(), report.fingerprint());
                                    }
                                    auditValidation(sender, scope, arenaId, completed);
                                    sendValidationReport(sender, title, completed);
                                }));
                    } catch (IOException | RuntimeException | LinkageError validationFailure) {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Could not validate captured configuration",
                                validationFailure);
                        sendLine(sender, "&cThe captured configuration could not be validated safely.");
                    }
                }));
    }

    private void applyArenaEdit(
            CommandSender sender,
            String arenaId,
            String editAction,
            String successMessage,
            ArenaEdit action) {
        if (!configurationMutationPending.compareAndSet(false, true)) {
            sendLine(sender, "&eA configuration mutation is already in progress.");
            return;
        }
        long expectedGeneration = configurationTools.generation();
        sendLine(sender, "&7Capturing and validating the arena edit asynchronously...");
        operations.submitRequired(configurationTools::captureFiles)
                .whenComplete((files, captureFailure) -> scheduleCommandReply(sender, () -> {
                    if (captureFailure != null) {
                        finishArenaEditFailure(
                                sender, arenaId, editAction, "io_failed", "unavailable",
                                "The configuration files could not be read safely.", captureFailure);
                        return;
                    }
                    if (!revalidateArenaEdit(sender, expectedGeneration)) {
                        finishArenaEditFailure(
                                sender, arenaId, editAction, "stale", "unavailable",
                                "The arena edit became stale before validation completed.", null);
                        return;
                    }
                    ArenaConfigurationEditor.EditPreparation preparation;
                    try {
                        preparation = action.prepare(files, expectedGeneration);
                    } catch (IllegalArgumentException exception) {
                        finishArenaEditFailure(
                                sender, arenaId, editAction, "invalid", "unavailable",
                                String.valueOf(exception.getMessage()), null);
                        return;
                    } catch (IOException | RuntimeException exception) {
                        finishArenaEditFailure(
                                sender, arenaId, editAction, "validation_failed", "unavailable",
                                "The captured configuration could not be validated safely.", exception);
                        return;
                    }
                    if (preparation.prepared().isEmpty()) {
                        configurationMutationPending.set(false);
                        games.auditArenaEdit(
                                operatorId(sender),
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
                    if (!revalidateArenaEdit(sender, expectedGeneration)) {
                        finishArenaEditFailure(
                                sender,
                                arenaId,
                                editAction,
                                "stale",
                                prepared.validation().fingerprint(),
                                "The arena edit became stale before persistence.",
                                null);
                        return;
                    }
                    operations.submitRequired(() -> {
                                if (!awaitMainApproval(() -> revalidateArenaEdit(
                                        sender, expectedGeneration))) {
                                    throw new IllegalStateException(
                                            "Arena edit became stale before durable commit");
                                }
                                ArenaConfigurationEditor.CommittedEdit committed =
                                        arenaEditor.commit(prepared);
                                if (!committedArenaEdit.compareAndSet(null, committed)) {
                                    arenaEditor.rollback(committed);
                                    throw new IllegalStateException(
                                            "Another committed arena edit is awaiting reconciliation");
                                }
                                try {
                                    arenaEditor.requireCommittedUnchanged(committed);
                                    return committed;
                                } catch (IOException verificationFailure) {
                                    try {
                                        arenaEditor.rollback(committed);
                                        committedArenaEdit.compareAndSet(committed, null);
                                    } catch (IOException rollbackFailure) {
                                        verificationFailure.addSuppressed(rollbackFailure);
                                    }
                                    throw verificationFailure;
                                }
                            })
                            .whenComplete((committed, commitFailure) ->
                                    scheduleCommandReply(sender, () -> finishCommittedArenaEdit(
                                            sender,
                                            arenaId,
                                            editAction,
                                            successMessage,
                                            expectedGeneration,
                                            committed,
                                            commitFailure)));
                }));
    }

    private void finishCommittedArenaEdit(
            CommandSender sender,
            String arenaId,
            String editAction,
            String successMessage,
            long expectedGeneration,
            ArenaConfigurationEditor.CommittedEdit committed,
            Throwable commitFailure) {
        if (commitFailure != null) {
            finishArenaEditFailure(
                    sender,
                    arenaId,
                    editAction,
                    "io_failed",
                    "unavailable",
                    "The edit was not persisted; the files changed or the I/O queue rejected it.",
                    commitFailure);
            return;
        }
        if (committed == null || committedArenaEdit.get() != committed) {
            finishArenaEditFailure(
                    sender,
                    arenaId,
                    editAction,
                    "ownership_lost",
                    "unavailable",
                    "The committed edit lost its exact reconciliation ownership.",
                    new IllegalStateException("Committed arena edit ownership was lost"));
            return;
        }
        ArenaConfigurationEditor.PreparedEdit prepared = committed.prepared();
        ConfigurationManager.ConfigurationSnapshot previous =
                configurationTools.activeSnapshot();
        boolean activated = false;
        try {
            activated = revalidateArenaEdit(sender, expectedGeneration)
                    && configurationActivator.activate(
                            prepared.snapshot(),
                            expectedGeneration,
                            () -> revalidateArenaEdit(sender, expectedGeneration));
        } catch (RuntimeException | LinkageError activationFailure) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not activate a durably committed arena edit",
                    activationFailure);
        }
        if (activated) {
            operations.submitRequired(() -> {
                arenaEditor.requireCommittedUnchanged(committed);
                return null;
            }).whenComplete((ignored, verificationFailure) ->
                    scheduleCommandReply(sender, () -> finishVerifiedArenaEdit(
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
        queueArenaRollback(
                sender,
                arenaId,
                editAction,
                prepared,
                committed,
                "The edit became stale or could not activate; config.yml was restored.");
    }

    private void finishVerifiedArenaEdit(
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
                && configurationTools.activeSnapshot() == prepared.snapshot()
                && committedArenaEdit.compareAndSet(committed, null)) {
            configurationMutationPending.set(false);
            games.auditArenaEdit(
                    operatorId(sender),
                    arenaId,
                    editAction,
                    "activated",
                    prepared.validation().fingerprint());
            sendLine(
                    sender,
                    "&a{{successMessage}} and activated the validated configuration.",
                    Map.of("successMessage", successMessage));
            sendField(sender, "Config hash", "sha256:" + prepared.validation().fingerprint());
            sendValidationWarnings(sender, prepared.validation());
            return;
        }

        if (verificationFailure != null) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "A committed arena edit changed during provisional runtime activation",
                    verificationFailure);
        }
        try {
            configurationActivator.activate(
                    previous,
                    expectedGeneration + 1L,
                    () -> configurationTools.generation() == expectedGeneration + 1L
                            && games.isConfigurationMutationIdle());
        } catch (RuntimeException | LinkageError rollbackFailure) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not restore the previous runtime after arena edit verification failed",
                    rollbackFailure);
        }
        queueArenaRollback(
                sender,
                arenaId,
                editAction,
                prepared,
                committed,
                "The committed file changed during activation; the previous runtime and config.yml "
                        + "were restored where ownership still matched.");
    }

    private void queueArenaRollback(
            CommandSender sender,
            String arenaId,
            String editAction,
            ArenaConfigurationEditor.PreparedEdit prepared,
            ArenaConfigurationEditor.CommittedEdit committed,
            String successMessage) {
        operations.submitRequired(() -> {
            arenaEditor.rollback(committed);
            committedArenaEdit.compareAndSet(committed, null);
            return null;
        }).whenComplete((ignored, rollbackFailure) -> scheduleCommandReply(sender, () -> {
            configurationMutationPending.set(false);
            if (rollbackFailure == null) {
                games.auditArenaEdit(
                        operatorId(sender),
                        arenaId,
                        editAction,
                        "rolled_back",
                        prepared.validation().fingerprint());
                sendLine(
                        sender,
                        "&c{{message}}",
                        Map.of("message", successMessage));
                return;
            }
            games.auditArenaEdit(
                    operatorId(sender),
                    arenaId,
                    editAction,
                    "rollback_failed",
                    prepared.validation().fingerprint());
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not restore config.yml after an arena edit failed to activate",
                    rollbackFailure);
            sendLine(
                    sender,
                    "&cThe edit and automatic rollback both failed; stop edits and inspect the console.");
        }));
    }

    private boolean revalidateArenaEdit(CommandSender sender, long expectedGeneration) {
        return arenaEditStateCurrent(
                expectedGeneration,
                configurationTools.generation(),
                stillAuthorized(sender, permissions().adminArena()),
                isArenaEditingIdle());
    }

    static boolean arenaEditStateCurrent(
            long expectedGeneration,
            long currentGeneration,
            boolean authorized,
            boolean idle) {
        return authorized && idle && expectedGeneration == currentGeneration;
    }

    private void finishArenaEditFailure(
            CommandSender sender,
            String arenaId,
            String editAction,
            String result,
            String fingerprint,
            String playerMessage,
            Throwable failure) {
        configurationMutationPending.set(false);
        games.auditArenaEdit(operatorId(sender), arenaId, editAction, result, fingerprint);
        if (failure != null) {
            plugin.getLogger().log(Level.SEVERE, "Could not complete an arena configuration edit", failure);
        }
        sendLine(sender, "&c{{error}}", Map.of("error", playerMessage));
    }

    private void sendValidationReport(
            CommandSender sender,
            String title,
            ConfigurationValidationReport report) {
        sendHeader(sender, title);
        sendField(sender, "Config hash", report.fingerprint().equals("unavailable")
                ? "unavailable"
                : "sha256:" + report.fingerprint());
        if (report.valid()) {
            sendLine(sender, "&aVALID &7- no configuration errors; active settings were not changed.");
        } else {
            sendLine(
                    sender,
                    "&cINVALID &7- {{errors}} error(s); active settings were not changed.",
                    Map.of("errors", report.errors().size()));
        }
        for (String error : report.errors()) {
            sendLine(sender, "&cERROR &7{{error}}", Map.of("error", error));
        }
        sendValidationWarnings(sender, report);
    }

    private void auditValidation(
            CommandSender sender,
            String scope,
            String arenaId,
            ConfigurationValidationReport report) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("actor", operatorKind(sender));
        fields.put("scope", scope);
        fields.put("result", report.valid() ? "valid" : "invalid");
        fields.put("errors", report.errors().size());
        fields.put("warnings", report.warnings().size());
        fields.put("fingerprint", report.fingerprint());
        if (arenaId != null) {
            fields.put("arena_id", arenaId);
        }
        operations.audit("admin.validate", operatorId(sender), null, Map.copyOf(fields));
    }

    private void sendValidationWarnings(
            CommandSender sender,
            ConfigurationValidationReport report) {
        for (String warning : report.warnings()) {
            sendLine(sender, "&eWARN &7{{warning}}", Map.of("warning", warning));
        }
        sendField(sender, "Warnings", Integer.toString(report.warnings().size()));
    }

    private void adminStatus(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminDebug())) {
            return;
        }
        if (args.length < 3) {
            showHealth(sender);
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            messages.send(sender, "chat.playerNotFound", Map.of("playerName", args[2]));
            return;
        }
        Optional<SessionStatus> status = games.sessionStatus(target.getUniqueId());
        if (status.isEmpty()) {
            sendLine(sender, "&e{{playerName}} is not in a WalkThePlank run.", Map.of(
                    "playerName", target.getName()));
            return;
        }
        SessionStatus session = status.orElseThrow();
        sendHeader(sender, "Run status: " + target.getName());
        sendField(sender, "Arena", session.arenaId());
        sendField(sender, "Score", Integer.toString(session.score()));
        sendField(sender, "Elapsed", session.elapsedSeconds() + " seconds");
        sendField(sender, "Idle", session.idleSeconds() + " seconds");
    }

    private void adminDoctor(CommandSender sender, String[] args) {
        if (!requireAdministrativePermission(sender, permissions().adminDebug())) {
            return;
        }
        if (args.length != 2) {
            sendLine(sender, "&cUsage: /walk admin doctor");
            return;
        }
        if (!doctorProbePending.compareAndSet(false, true)) {
            sendLine(sender, "&eA WalkThePlank doctor probe is already running.");
            return;
        }

        sendLine(
                sender,
                "&7Running a read-only SQLite check asynchronously; "
                        + "the privacy-safe report will follow.");
        operations.audit("admin.doctor", operatorId(sender), null, Map.of(
                "actor", operatorKind(sender),
                "result", "started"));

        CompletableFuture<DatabaseDoctorReport> probe;
        try {
            probe = scores.inspectDatabase();
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
            scheduleCommandReply(sender, () -> {
                try {
                    int warnings = showDoctorReport(sender, database);
                    operations.audit("admin.doctor", operatorId(sender), null, Map.of(
                            "actor", operatorKind(sender),
                            "result", warnings == 0 ? "pass" : "warn",
                            "warnings", warnings,
                            "sqlite_quick_check", database.quickCheckPassed()));
                } catch (RuntimeException | LinkageError reportFailure) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not render the privacy-safe WalkThePlank doctor report",
                            reportFailure);
                    sendLine(
                            sender,
                            "&cThe doctor report could not be rendered safely; "
                                    + "review the server log.");
                }
            });
        });
    }

    private void reportDoctorFailure(CommandSender sender, Throwable failure) {
        plugin.getLogger().log(
                Level.WARNING,
                "Could not complete the read-only WalkThePlank SQLite doctor probe",
                failure);
        scheduleCommandReply(sender, () -> {
            operations.audit("admin.doctor", operatorId(sender), null, Map.of(
                    "actor", operatorKind(sender),
                    "result", "sqlite_probe_failed"));
            sendField(sender, "Doctor result", "SQLITE_PROBE_FAILED");
            sendLine(
                    sender,
                    "&cThe read-only SQLite doctor probe failed. "
                            + "No path, SQL text, or database content is included here; "
                            + "review the protected server log.");
        });
    }

    private int showDoctorReport(
            CommandSender sender,
            DatabaseDoctorReport database) {
        RuntimeSettings current = settings.get();
        ScoreSnapshot scoreSnapshot = scores.snapshot();
        DurabilityMetrics durability = scores.durabilityMetrics();
        var runtimeMetrics = games.operationalMetrics();
        var operationsIo = operations.ioStatus();
        var recoveryDurability = games.recoveryDurabilityStatus();
        QueueStatus queue = games.queueStatus();
        GameManager.TaskHealth gameTasks = games.taskHealth();
        MenuService.Health menuHealth = menus.health();
        var recovery = games.playerRecoveryHealth();

        List<BukkitTask> pluginTasks = plugin.getServer()
                .getScheduler()
                .getPendingTasks()
                .stream()
                .filter(task -> task.getOwner().equals(plugin))
                .toList();
        long synchronousTasks = pluginTasks.stream().filter(BukkitTask::isSync).count();
        long asynchronousTasks = pluginTasks.size() - synchronousTasks;

        int runtimeJava = Runtime.version().feature();
        boolean javaMatches = Integer.toString(runtimeJava).equals(buildInfo.javaTarget());
        String runtimeMinecraft = plugin.getServer().getMinecraftVersion();
        boolean paperMatches = runtimeMinecraft.equals(buildInfo.paperTarget());

        List<String> commandRoots = current.allowedRewardCommandRoots().stream()
                .sorted()
                .toList();
        int missingCommandRoots = 0;
        for (String root : commandRoots) {
            if (plugin.getServer().getCommandMap().getCommand(root) == null) {
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
        if (buildInfo.sourceDirty()) {
            warnings++;
        }
        if (!javaMatches) {
            warnings++;
        }
        if (!paperMatches) {
            warnings++;
        }
        if (!database.quickCheckPassed()) {
            warnings++;
        }
        if (current.finishCommandsEnabled() && missingCommandRoots > 0) {
            warnings++;
        }
        if (games.quarantinedArenas() > 0 || games.conflictedRestorations() > 0) {
            warnings++;
        }
        if (!recovery.healthy()) {
            warnings++;
        }
        if (unknownPlans > 0L || unknownSteps > 0L || dispatchingSteps > 0L) {
            warnings++;
        }
        if (durability.lastDatabaseFailure().isPresent()) {
            warnings++;
        }
        if (runtimeMetrics.degraded()) {
            warnings++;
        }
        if (operationsIo.failed() > 0L || operationsIo.rejected() > 0L || operationsIo.closing()) {
            warnings++;
        }
        if (recoveryDurability.failed() > 0L
                || recoveryDurability.rejected() > 0L
                || recoveryDurability.closing()) {
            warnings++;
        }

        sendHeader(sender, "Doctor support report");
        sendField(sender, "Release", buildInfo.releaseLabel());
        sendField(sender, "Artifact", buildInfo.artifactFile());
        sendField(sender, "Source commit", buildInfo.sourceCommit());
        sendField(sender, "Source state", buildInfo.sourceDirty() ? "DIRTY" : "clean");
        sendField(
                sender,
                "Java target/runtime",
                buildInfo.javaTarget() + " / " + System.getProperty("java.version", "unknown")
                        + (javaMatches ? " (match)" : " (MISMATCH)"));
        sendField(
                sender,
                "Paper target/runtime",
                buildInfo.paperApiVersion() + " / " + plugin.getServer().getVersion()
                        + (paperMatches ? " (match)" : " (MISMATCH)"));

        sendHeader(sender, "Doctor: hooks and command roots");
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
        sendField(
                sender,
                "Reward commands",
                current.finishCommandsEnabled() ? "enabled" : "disabled");
        sendField(
                sender,
                "Configured roots",
                commandRoots.size() + " total / " + missingCommandRoots + " missing");
        for (String root : commandRoots) {
            boolean available = plugin.getServer().getCommandMap().getCommand(root) != null;
            sendField(sender, "Root " + root, available ? "available" : "missing");
        }

        sendHeader(sender, "Doctor: SQLite and durability");
        sendField(
                sender,
                "SQLite quick_check",
                database.quickCheckPassed() ? "ok" : "FAILED");
        sendField(sender, "Database size", database.databaseBytes() + " bytes");
        sendField(sender, "WAL size", database.walBytes() + " bytes");
        sendField(sender, "Probe latency", database.latencyMillis() + " ms");
        sendField(
                sender,
                "Migration backups",
                Integer.toString(database.migrationBackupCount()));
        sendField(sender, "Score rows", Integer.toString(scoreSnapshot.totalEntries()));
        sendField(sender, "Pending database writes", Integer.toString(durability.pendingMutations()));
        sendField(
                sender,
                "Restoration journal",
                games.pendingRestorations() + " pending / "
                        + games.conflictedRestorations() + " conflicted / "
                        + games.quarantinedArenas() + " quarantined arenas");
        sendField(
                sender,
                "Player recovery journal",
                recovery.pendingRecords() + " pending / "
                        + recovery.invalidRecords() + " invalid / "
                        + (recovery.healthy() ? "healthy" : "degraded"));
        sendField(
                sender,
                "Uncertain rewards",
                unknownPlans + " UNKNOWN plans / "
                        + unknownSteps + " UNKNOWN steps / "
                        + dispatchingSteps + " DISPATCHING steps");
        sendField(
                sender,
                "Unfinished rewards",
                pendingPlans + " PENDING plans / "
                        + inProgressPlans + " IN_PROGRESS plans");
        durability.lastDatabaseFailure().ifPresent(lastFailure -> sendField(
                sender,
                "Last database failure",
                lastFailure.category() + "/" + lastFailure.exceptionType()));

        sendHeader(sender, "Doctor: queue and tasks");
        sendField(
                sender,
                "Queue",
                (queue.enabled() ? "enabled" : "disabled") + " / "
                        + (queue.paused() ? "paused" : "running") + " / "
                        + queue.total() + " total / "
                        + queue.waiting() + " waiting / "
                        + queue.ready() + " ready");
        sendField(
                sender,
                "Gameplay",
                games.activeSessions() + " active / "
                        + gameTasks.pendingStarts() + " pending starts / "
                        + gameTasks.pendingStartAbandonments() + " start cleanups / "
                        + gameTasks.pendingExternalTeleportChecks() + " teleport checks / "
                        + gameTasks.pendingPlayerRecoveryLookups() + " recovery lookups / "
                        + gameTasks.pendingPlayerRecoveryCompletions() + " recovery completions");
        sendField(
                sender,
                "GUI",
                menuHealth.openSessions() + " sessions / "
                        + menuHealth.pendingActions() + " pending actions");
        sendField(
                sender,
                "Paper tasks",
                pluginTasks.size() + " owned / "
                        + synchronousTasks + " sync / "
                        + asynchronousTasks + " async");
        sendField(
                sender,
                "Operations I/O",
                operationsIo.queued() + "/" + operationsIo.queueCapacity() + " queued / "
                        + operationsIo.active() + " active / "
                        + operationsIo.requiredAccepted() + " required accepted / "
                        + operationsIo.bestEffortAccepted() + " best-effort accepted / "
                        + operationsIo.completed() + " completed / "
                        + operationsIo.failed() + " failed / "
                        + operationsIo.rejected() + " rejected / "
                        + (operationsIo.terminated()
                                ? "terminated"
                                : operationsIo.closing() ? "closing" : "open"));
        sendField(
                sender,
                "Recovery durability",
                recoveryDurability.queued() + "/" + recoveryDurability.queueCapacity()
                        + " queued / "
                        + recoveryDurability.active() + " active / "
                        + recoveryDurability.accepted() + " accepted / "
                        + recoveryDurability.completed() + " completed / "
                        + recoveryDurability.failed() + " failed / "
                        + recoveryDurability.rejected() + " rejected / "
                        + (recoveryDurability.terminated()
                                ? "terminated"
                                : recoveryDurability.closing() ? "closing" : "open"));
        sendField(
                sender,
                "Runtime failures",
                runtimeMetrics.restorationFailures() + " restoration / "
                        + runtimeMetrics.rewardFailures() + " reward / "
                        + runtimeMetrics.repositoryFailures() + " repository / "
                        + runtimeMetrics.auditFailures() + " audit");
        if (runtimeMetrics.lastFailure() != null) {
            sendField(
                    sender,
                    "Last runtime failure",
                    runtimeMetrics.lastFailure().subsystem() + "/"
                            + runtimeMetrics.lastFailure().summary());
        }

        sendField(
                sender,
                "Result",
                warnings == 0 ? "PASS" : "WARN (" + warnings + ")");
        sendLine(
                sender,
                "&7Privacy: no player/season names, coordinates, filesystem paths, "
                        + "raw reward command lines, credentials, SQL text, or exception messages "
                        + "are included.");
        return warnings;
    }

    private void sendDoctorHook(
            CommandSender sender,
            String pluginName,
            boolean placeholderApi) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (dependency == null) {
            sendField(sender, pluginName, "not installed");
            return;
        }
        String state = dependency.isEnabled() ? "enabled" : "disabled";
        if (placeholderApi) {
            state += placeholderRegistered.getAsBoolean()
                    ? ", expansion active"
                    : ", expansion inactive";
        } else {
            state += ", presence only";
        }
        sendField(
                sender,
                pluginName,
                dependency.getPluginMeta().getVersion() + " (" + state + ")");
    }

    private void debug(CommandSender sender, String requestedPage) {
        if (!requireAdministrativePermission(sender, permissions().adminDebug())) {
            return;
        }
        String page = requestedPage.toLowerCase(Locale.ROOT);
        if (!DEBUG_PAGES.contains(page)) {
            sendLine(sender, "&cUnknown debug page. Use: {{pages}}", Map.of(
                    "pages", String.join(", ", DEBUG_PAGES)));
            return;
        }
        if (page.equals("all")) {
            for (String debugPage : DEBUG_PAGES) {
                if (!debugPage.equals("all")) {
                    showDebugPage(sender, debugPage);
                }
            }
            return;
        }
        showDebugPage(sender, page);
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
            default -> throw new IllegalArgumentException("Unknown debug page " + page);
        }
    }

    private void showOverview(CommandSender sender) {
        sendHeader(sender, "Diagnostics: overview");
        sendField(sender, "Release", buildInfo.releaseLabel());
        sendField(sender, "Artifact", buildInfo.artifactFile());
        sendField(sender, "Source", buildInfo.sourceLabel());
        sendField(sender, "Runtime", System.getProperty("java.version") + " / "
                + plugin.getServer().getName() + " " + plugin.getServer().getMinecraftVersion());
        sendField(sender, "Compile targets", "Java " + buildInfo.javaTarget() + ", Paper "
                + buildInfo.paperApiVersion() + ", PlaceholderAPI " + buildInfo.placeholderApiVersion());
    }

    private void showHealth(CommandSender sender) {
        ScoreSnapshot snapshot = scores.snapshot();
        DurabilityMetrics durability = scores.durabilityMetrics();
        var runtime = games.operationalMetrics();
        QueueStatus queue = games.queueStatus(new UUID(0L, 0L));
        sendHeader(sender, "Diagnostics: health");
        sendField(sender, "Arenas", games.activeSessions() + " active / "
                + games.availableArenas() + " free / " + games.totalArenas() + " configured");
        sendField(sender, "Restoration", games.quarantinedArenas() + " quarantined / "
                + games.pendingRestorations() + " pending / "
                + games.conflictedRestorations() + " conflicted");
        var playerRecovery = games.playerRecoveryHealth();
        sendField(sender, "Player recovery", playerRecovery.pendingRecords() + " pending / "
                + playerRecovery.invalidRecords() + " invalid / "
                + (playerRecovery.healthy() ? "healthy" : "degraded"));
        sendField(sender, "Database", "SQLite, " + snapshot.totalEntries() + " score rows");
        sendField(sender, "Snapshot", SNAPSHOT_TIME.format(snapshot.capturedAt()));
        sendField(sender, "Writes", durability.pendingMutations() + " pending; last success "
                + durability.lastSuccessfulWriteAt().map(SNAPSHOT_TIME::format).orElse("none"));
        sendField(sender, "Runs", durability.retainedRuns() + " retained; "
                + runtime.sessionsStarted() + " started / " + runtime.sessionsEnded() + " ended this uptime");
        sendField(sender, "Queue", queue.total() + " player(s), "
                + (queue.paused() ? "paused" : "running"));
        sendField(sender, "Season", durability.activeSeason()
                .map(season -> season.name() + " (" + season.id() + ")")
                .orElse("none active"));
        sendField(sender, "Reward plans", settings.get().finishCommandsEnabled()
                ? durability.rewardPlansByStatus().toString()
                : "disabled");
        durability.lastDatabaseFailure().ifPresent(failure -> sendField(
                sender,
                "Last database failure",
                failure.category() + "/" + failure.exceptionType() + " at "
                        + SNAPSHOT_TIME.format(failure.occurredAt())));
        if (runtime.lastFailure() != null) {
            sendField(sender, "Last runtime failure", runtime.lastFailure().subsystem() + "/"
                    + runtime.lastFailure().summary() + " at "
                    + SNAPSHOT_TIME.format(runtime.lastFailure().occurredAt()));
        }
    }

    private void showHooks(CommandSender sender) {
        sendHeader(sender, "Diagnostics: hooks");
        sendPlaceholderHook(sender);
        for (String name : List.of("CMI", "CMILib", "Vault", "UltimateFireworks", "PyroWelcomesPro", "PyroLib")) {
            sendPluginPresence(sender, name);
        }
    }

    private void showCommands(CommandSender sender) {
        sendHeader(sender, "Diagnostics: commands");
        sendLine(sender, "&7Player: &f/walk, play, queue, leave, stats, top, info, help");
        sendLine(
                sender,
                "&7Staff: &f/walk admin open, reload, stop, recover, validate, arena, queue, "
                        + "season, export, reward, run, status, debug, doctor");
        sendLine(sender, "&7Compatibility: &f/walk open, reload, version; aliases /infp and /infinityparkour");
    }

    private void showPermissions(CommandSender sender) {
        sendHeader(sender, "Diagnostics: permissions");
        PermissionSettings permissionSettings = permissions();
        for (Map.Entry<String, String> entry : Map.ofEntries(
                Map.entry("open", permissionSettings.openGui()),
                Map.entry("play", permissionSettings.playGame()),
                Map.entry("leave", permissionSettings.leaveArena()),
                Map.entry("stats", permissionSettings.stats()),
                Map.entry("top", permissionSettings.top()),
                Map.entry("info", permissionSettings.info()),
                Map.entry("help", permissionSettings.help()),
                Map.entry("admin", permissionSettings.admin()),
                Map.entry("reload", permissionSettings.reload()),
                Map.entry("admin.open", permissionSettings.adminOpen()),
                Map.entry("admin.debug", permissionSettings.adminDebug()),
                Map.entry("admin.stop", permissionSettings.adminStop()),
                Map.entry("admin.recover", permissionSettings.adminRecover()),
                Map.entry("admin.validate", permissionSettings.adminValidate()),
                Map.entry("admin.arena", permissionSettings.adminArena()),
                Map.entry("admin.queue", permissionSettings.adminQueue()),
                Map.entry("admin.season", permissionSettings.adminSeason()),
                Map.entry("admin.export", permissionSettings.adminExport()),
                Map.entry("admin.reward", permissionSettings.adminReward()),
                Map.entry("admin.investigate", permissionSettings.adminInvestigate())).entrySet()) {
            String stateColor = sender.hasPermission(entry.getValue()) ? "&a" : "&c";
            sendLine(
                    sender,
                    "&7{{permissionKey}}: &f{{permissionName}} " + stateColor + "{{state}}",
                    Map.of(
                            "permissionKey", entry.getKey(),
                            "permissionName", entry.getValue(),
                            "state", sender.hasPermission(entry.getValue()) ? "YES" : "NO"));
        }
    }

    private void showPlaceholders(CommandSender sender) {
        sendHeader(sender, "Diagnostics: placeholders");
        sendField(sender, "Registration", placeholderRegistered.getAsBoolean() ? "active" : "inactive");
        sendLine(sender, "&f%infinityparkour_version%");
        sendLine(sender, "&f%infinityparkour_score% &7| rank | percentile | current_score | in_game");
        sendLine(sender, "&f%infinityparkour_total_players%");
        sendLine(sender, "&f%infinityparkour_active_arenas% &7| available_arenas | total_arenas | quarantined_arenas");
        sendLine(sender, "&f%infinityparkour_queue_total% &7| queue_waiting | queue_ready_count | queue_enabled | queue_paused");
        sendLine(sender, "&f%infinityparkour_queue_position% &7| queue_ready");
        sendLine(sender, "&f%infinityparkour_current_arena% &7| elapsed_seconds | idle_seconds");
        sendLine(sender, "&f%infinityparkour_previous_best% &7| best_delta");
        sendLine(sender, "&f%infinityparkour_top_<1-10>_<name|score|rank>%");
        sendLine(sender, "&f%infinityparkour_active_season_id% &7| active_season_name | season_total_players");
        sendLine(sender, "&f%infinityparkour_season_score% &7| season_rank | season_percentile");
        sendLine(sender, "&f%infinityparkour_season_top_<1-10>_<name|score|rank>%");
    }

    private void showConfig(CommandSender sender) {
        RuntimeSettings current = settings.get();
        sendHeader(sender, "Diagnostics: safe config");
        sendField(sender, "Arenas", Integer.toString(current.arenas().size()));
        sendField(sender, "Blocks", current.parkourBlocks().toString());
        sendField(sender, "Bounds", "radius " + current.horizontalRadius()
                + ", fall distance " + current.fallDistance());
        sendField(sender, "Timeouts", current.maximumRunSeconds() + "s maximum / "
                + current.idleTimeoutSeconds() + "s idle");
        sendField(sender, "Generation", current.onlyReplaceAir() ? "air only" : "replace allowed");
        sendField(sender, "Particles", current.particlesEnabled()
                ? current.particle() + " x" + current.particleCount() : "disabled");
        sendField(sender, "Rewards", current.finishCommandsEnabled()
                ? current.rewardTiers().size() + " configured tier(s), personal-best-only="
                        + current.rewardsOnlyOnPersonalBest()
                : "disabled");
    }

    private void sendPlaceholderHook(CommandSender sender) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (dependency == null) {
            sendField(sender, "PlaceholderAPI", "not installed");
            return;
        }
        String state = dependency.isEnabled() ? "enabled" : "disabled";
        state += placeholderRegistered.getAsBoolean() ? ", expansion active" : ", expansion inactive";
        sendField(sender, "PlaceholderAPI", dependency.getPluginMeta().getVersion() + " (" + state + ")");
    }

    private void sendPluginPresence(CommandSender sender, String pluginName) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (dependency == null) {
            sendField(sender, pluginName, "not installed");
            return;
        }
        String state = dependency.isEnabled() ? "enabled" : "disabled";
        sendField(sender, pluginName,
                dependency.getPluginMeta().getVersion() + " (" + state + ", presence only)");
    }

    private void openMenu(CommandSender sender, Player player) {
        if (!requirePermission(sender, permissions().openGui())) {
            return;
        }
        menus.open(player);
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "chat.playerOnly");
        return null;
    }

    private PermissionSettings permissions() {
        return settings.get().permissions();
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (has(sender, permission)) {
            return true;
        }
        deny(sender, permission);
        return false;
    }

    private boolean requireAdministrativePermission(CommandSender sender, String permission) {
        if (hasAdministrativePermission(sender, permission)) {
            return true;
        }
        deny(sender, permission);
        return false;
    }

    private boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.hasPermission(permissions().admin());
    }

    private boolean hasAdministrativePermission(CommandSender sender, String permission) {
        return sender.hasPermission(permissions().admin()) || sender.hasPermission(permission);
    }

    private boolean stillAuthorized(CommandSender sender, String permission) {
        if (!hasAdministrativePermission(sender, permission)) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.isOnline()
                && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    private boolean hasAnyAdminPermission(CommandSender sender) {
        PermissionSettings permissionSettings = permissions();
        return sender.hasPermission(permissionSettings.admin())
                || sender.hasPermission(permissionSettings.reload())
                || sender.hasPermission(permissionSettings.adminOpen())
                || sender.hasPermission(permissionSettings.adminDebug())
                || sender.hasPermission(permissionSettings.adminStop())
                || sender.hasPermission(permissionSettings.adminRecover())
                || sender.hasPermission(permissionSettings.adminValidate())
                || sender.hasPermission(permissionSettings.adminArena())
                || sender.hasPermission(permissionSettings.adminQueue())
                || sender.hasPermission(permissionSettings.adminSeason())
                || sender.hasPermission(permissionSettings.adminExport())
                || sender.hasPermission(permissionSettings.adminReward())
                || sender.hasPermission(permissionSettings.adminInvestigate());
    }

    private void deny(CommandSender sender, String permission) {
        messages.send(sender, "chat.noPermission", Map.of("permissionName", permission));
    }

    private void addHelp(CommandSender sender, String permission, String syntax, String description) {
        if (has(sender, permission)) {
            sendCommandHelp(sender, syntax, description);
        }
    }

    private void addAdminHelp(CommandSender sender, String permission, String syntax, String description) {
        if (hasAdministrativePermission(sender, permission)) {
            sendCommandHelp(sender, syntax, description);
        }
    }

    private void addIfAllowed(List<String> choices, CommandSender sender, String permission, String value) {
        if (has(sender, permission)) {
            choices.add(value);
        }
    }

    private void addAdminIfAllowed(List<String> choices, CommandSender sender, String permission, String value) {
        if (hasAdministrativePermission(sender, permission)) {
            choices.add(value);
        }
    }

    private void sendHeader(CommandSender sender, String title) {
        sendLine(
                sender,
                "&7---------- &3&lWalkThePlank &8| &f{{title}} &7----------",
                Map.of("title", title));
    }

    private void sendField(CommandSender sender, String name, String value) {
        sendLine(sender, "&7{{name}}: &f{{value}}", Map.of("name", name, "value", value));
    }

    private void sendCommandHelp(CommandSender sender, String syntax, String description) {
        sendLine(
                sender,
                "&3{{syntax}} &7- &f{{description}}",
                Map.of("syntax", syntax, "description", description));
    }

    private void sendLine(CommandSender sender, String line) {
        sender.sendMessage(messages.deserialize(line));
    }

    private void sendLine(
            CommandSender sender,
            String trustedTemplate,
            Map<String, ?> literalReplacements) {
        sender.sendMessage(messages.render(trustedTemplate, literalReplacements));
    }

    private List<String> onlinePlayerNames() {
        return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).sorted().toList();
    }

    private List<String> configuredArenaIds() {
        return settings.get().arenas().stream()
                .map(Arena::id)
                .filter(ArenaId::isValid)
                .sorted()
                .toList();
    }

    private static List<String> complete(String input, List<String> choices) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .sorted()
                .toList();
    }

    private static String[] shift(String[] args) {
        String[] shifted = new String[Math.max(0, args.length - 1)];
        if (shifted.length > 0) {
            System.arraycopy(args, 1, shifted, 0, shifted.length);
        }
        return shifted;
    }

    private static UUID operatorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private static String operatorKind(CommandSender sender) {
        return sender instanceof Player ? "player" : "system";
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

    @FunctionalInterface
    private interface ArenaEdit {
        ArenaConfigurationEditor.EditPreparation prepare(
                ConfigurationManager.ConfigurationFiles files,
                long expectedGeneration) throws IOException;
    }

    @FunctionalInterface
    private interface SeasonTransition {
        CompletableFuture<Season> apply(UUID id, Instant transitionedAt);
    }

    private enum ArenaLocationEdit {
        CREATE("create", "Created"),
        SET_START("setstart", "Updated the start for"),
        SET_EXIT("setexit", "Updated the exit for");

        private final String command;
        private final String successVerb;

        ArenaLocationEdit(String command, String successVerb) {
            this.command = command;
            this.successVerb = successVerb;
        }

        String command() {
            return command;
        }

        String successVerb() {
            return successVerb;
        }
    }
}
