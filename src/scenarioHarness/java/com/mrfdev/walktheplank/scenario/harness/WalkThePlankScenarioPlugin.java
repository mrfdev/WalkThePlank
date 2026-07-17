package com.mrfdev.walktheplank.scenario.harness;

import com.mrfdev.walktheplank.api.WalkThePlankApi;
import com.mrfdev.walktheplank.api.event.WalkRunEndEvent;
import com.mrfdev.walktheplank.api.event.WalkRunStartEvent;
import com.mrfdev.walktheplank.game.SessionEndReason;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * Test-only Paper scenario driver.
 *
 * <p>This plugin deliberately refuses to enable against the production target. The disposable
 * profile must provide an instrumented target with {@link ScenarioFailpointBridge#BRIDGE_CLASS}
 * and explicit startup properties.</p>
 */
public final class WalkThePlankScenarioPlugin extends JavaPlugin
        implements Listener, TabExecutor {
    private static final String TARGET_PLUGIN = "InfinityParkour";
    private static final String PROFILE_PROPERTY = "walktheplank.scenario.profile";
    private static final String PROFILE_ROOT_PROPERTY = "walktheplank.scenario.root";
    private static final String PROFILE_NONCE_PROPERTY = "walktheplank.scenario.nonce";
    private static final String PROFILE_MARKER = ".walktheplank-disposable-profile";
    private static final String PLACEHOLDER_PROPERTY =
            "walktheplank.scenario.placeholderapi";
    private static final String PERMISSION = "walktheplank.scenario.admin";
    private static final String PAPI_CLASS = "me.clip.placeholderapi.PlaceholderAPI";
    private static final String PAPI_IDENTIFIER = "infinityparkour";
    private static final String MENU_HOLDER_CLASS =
            "com.mrfdev.walktheplank.gui.MenuService$ParkourMenu";
    private static final Pattern SAFE_POINT = Pattern.compile("[a-z0-9_.-]{1,96}");
    private static final Pattern SAFE_NONCE = Pattern.compile("[A-Za-z0-9._-]{8,128}");
    private static final List<String> FAILPOINT_ACTIONS =
            List.of("HALT", "THROW", "BLOCK");
    private static final List<String> KNOWN_FAILPOINTS = List.of(
            "player_journal.after_temp_write",
            "player_journal.after_temp_fsync",
            "player_journal.after_rename",
            "player_journal.after_directory_fsync",
            "player_journal.after_delete",
            "restoration_journal.after_temp_write",
            "restoration_journal.after_temp_fsync",
            "restoration_journal.after_rename",
            "restoration_journal.after_directory_fsync",
            "restoration_journal.after_delete",
            "block.after_place",
            "block.after_restore",
            "teleport.after_start",
            "teleport.after_return",
            "score.after_commit",
            "reward_claim.after_commit",
            "reward.after_dispatch",
            "reward_outcome.after_commit",
            "export.csv.after_rename",
            "export.json.after_rename",
            "config.backup.after_rename",
            "config.candidate.after_rename",
            "config.after_disk_commit",
            "config.after_runtime_commit");

    private final EnumMap<SessionEndReason, Integer> observedEndReasons =
            new EnumMap<>(SessionEndReason.class);
    private final Map<UUID, SessionEndReason> lastEndReasons = new HashMap<>();
    private final Map<UUID, SessionEndReason> expectedEndReasons = new HashMap<>();
    private final Map<UUID, TeleportProbe> teleportProbes = new HashMap<>();
    private final Map<UUID, GuiProbe> guiProbes = new HashMap<>();
    private final Map<UUID, ReconnectProbe> reconnectProbes = new HashMap<>();
    private int passCount;
    private int failureCount;
    private boolean headlessRunning;
    private Plugin target;
    private ScenarioFailpointBridge failpoints;
    private RestartMarkerStore restartMarkers;
    private BukkitTask contentionTask;

    @Override
    public void onEnable() {
        restartMarkers = new RestartMarkerStore(getDataFolder().toPath());
        if (!"true".equalsIgnoreCase(System.getProperty(PROFILE_PROPERTY, ""))) {
            failStartup("profile-guard", "missing -D" + PROFILE_PROPERTY + "=true");
            return;
        }

        Plugin candidate = getServer().getPluginManager().getPlugin(TARGET_PLUGIN);
        if (candidate == null || !candidate.isEnabled()) {
            failStartup("target-enabled", "InfinityParkour is missing or disabled");
            return;
        }
        target = candidate;
        try {
            Path profileRoot = requireDisposableProfileRoot();
            requireExactDataPath(
                    target.getDataFolder().toPath(),
                    profileRoot.resolve("plugins").resolve(TARGET_PLUGIN));
            requireExactDataPath(
                    getDataFolder().toPath(),
                    profileRoot.resolve("plugins").resolve("WalkThePlank-ScenarioHarness"));
        } catch (IOException | RuntimeException exception) {
            failStartup(
                    "profile-path",
                    "target and harness must use the exact generated disposable profile");
            return;
        }

        try {
            failpoints = ScenarioFailpointBridge.load(target);
            failpoints.status();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            failStartup("bridge", failureDetail(exception));
            return;
        }

        PluginCommand scenarioCommand = getCommand("walktheplankscenario");
        if (scenarioCommand == null) {
            failStartup("descriptor", "walktheplankscenario command is missing");
            return;
        }
        scenarioCommand.setExecutor(this);
        scenarioCommand.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        pass("target-enabled", "version=" + clean(target.getPluginMeta().getVersion()));
        if (!verifyApi("startup-api") || !verifyPlaceholderExpectation()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            info("bridge", "status=" + clean(failpoints.status()));
        } catch (ReflectiveOperationException exception) {
            failStartup("bridge-status", failureDetail(exception));
            return;
        }
        inspectRestartMarker();
        info(
                "ready",
                "commands=/wtpscenario profile=disposable client-dependent-cases=PENDING");
    }

    @Override
    public void onDisable() {
        cancelContentionTask();
        if (failpoints != null) {
            try {
                failpoints.clear();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                getLogger().warning(
                        "WTP-SCENARIO FAIL failpoint-clear-on-disable "
                                + failureDetail(exception));
            }
        }
        teleportProbes.clear();
        guiProbes.clear();
        reconnectProbes.clear();
        expectedEndReasons.clear();
        headlessRunning = false;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("You need " + PERMISSION + " to run destructive scenarios.");
            return true;
        }
        String root = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (root) {
            case "run" -> runGroup(sender, args);
            case "headless" -> runHeadless();
            case "lifecycle" -> runLifecycle();
            case "status" -> showStatus(sender);
            case "failpoint" -> failpointCommand(sender, args);
            case "restart" -> restartCommand(args);
            case "teleport" -> teleportCommand(sender, args);
            case "gui" -> guiCommand(sender, args);
            case "reconnect" -> reconnectCommand(sender, args);
            case "exits" -> exitsCommand(sender, args);
            case "contention", "queue" -> runContention();
            case "help" -> sendHelp(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(
                    args[0],
                    List.of(
                            "run",
                            "status",
                            "headless",
                            "lifecycle",
                            "failpoint",
                            "restart",
                            "teleport",
                            "gui",
                            "reconnect",
                            "exits",
                            "contention",
                            "help"));
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (root) {
                case "run" -> matching(args[1], List.of("headless", "lifecycle", "player", "queue"));
                case "failpoint" ->
                        matching(args[1], List.of("list", "status", "arm", "clear", "release"));
                case "restart" -> matching(args[1], List.of("prepare", "status", "clear"));
                case "teleport" -> matching(args[1], List.of("cancel", "retarget", "status", "clear"));
                case "gui" -> matching(args[1], List.of("begin", "status", "stale", "clear"));
                case "reconnect" -> matching(args[1], List.of("arm", "status", "clear"));
                case "exits" -> matching(args[1], List.of("expect", "status", "clear"));
                default -> List.of();
            };
        }
        if (root.equals("failpoint") && args.length == 3 && args[1].equalsIgnoreCase("arm")) {
            return matching(args[2], KNOWN_FAILPOINTS);
        }
        if (root.equals("failpoint") && args.length == 4 && args[1].equalsIgnoreCase("arm")) {
            return matching(args[3], FAILPOINT_ACTIONS);
        }
        if (root.equals("exits") && args.length == 3 && args[1].equalsIgnoreCase("expect")) {
            return matching(
                    args[2],
                    EnumSet.allOf(SessionEndReason.class).stream().map(Enum::name).toList());
        }
        return List.of();
    }

    private void runGroup(CommandSender sender, String[] args) {
        String group = args.length < 2 ? "headless" : args[1].toLowerCase(Locale.ROOT);
        switch (group) {
            case "headless" -> runHeadless();
            case "lifecycle" -> runLifecycle();
            case "queue" -> runContention();
            case "player" -> {
                pending(
                        "player-suite",
                        "requires a real client: use gui, teleport, reconnect and exits commands");
                if (sender instanceof Player player) {
                    beginGuiCapture(player);
                }
            }
            default -> sender.sendMessage(
                    "Usage: /wtpscenario run <headless|lifecycle|player|queue>");
        }
    }

    private void runHeadless() {
        if (headlessRunning) {
            info("headless", "status=ALREADY_RUNNING");
            return;
        }
        headlessRunning = true;
        boolean baseline = target != null
                && target.isEnabled()
                && verifyApi("headless-api")
                && verifyPlaceholderExpectation();
        if (!baseline) {
            headlessRunning = false;
            fail("headless", "baseline checks failed");
            return;
        }

        boolean dispatched =
                getServer().dispatchCommand(getServer().getConsoleSender(), "walk admin reload");
        if (!dispatched) {
            headlessRunning = false;
            fail("config-reload", "console command was not accepted");
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            if (target == null || !target.isEnabled() || api() == null) {
                headlessRunning = false;
                fail("config-reload", "target or API unavailable after reload");
                return;
            }
            pass("config-reload", "command=/walk admin reload target-enabled=true");
            boolean disabled = runLifecycleInternal();
            headlessRunning = false;
            if (disabled) {
                pass("headless", "reload=true disable=true service-removed=true");
            } else {
                fail("headless", "disable lifecycle failed");
            }
        }, 10L);
    }

    private void runLifecycle() {
        runLifecycleInternal();
    }

    private boolean runLifecycleInternal() {
        cancelContentionTask();
        Plugin current = target;
        if (current == null || !current.isEnabled()) {
            fail("lifecycle", "target is unavailable before disable");
            return false;
        }
        try {
            getServer().getPluginManager().disablePlugin(current);
            boolean disabled = !current.isEnabled();
            boolean serviceRemoved = api() == null;
            if (!disabled || !serviceRemoved) {
                fail(
                        "lifecycle-disable",
                        "disabled=" + disabled + " service-removed=" + serviceRemoved);
                return false;
            }
            pass("lifecycle-disable", "service-removed=true");
            return true;
        } catch (RuntimeException | LinkageError exception) {
            fail("lifecycle", failureDetail(exception));
            return false;
        }
    }

    private void failpointCommand(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "list" -> {
                    sender.sendMessage(String.join(", ", KNOWN_FAILPOINTS));
                    info("failpoint-list", "count=" + KNOWN_FAILPOINTS.size());
                }
                case "status" -> info("failpoint-status", "state=" + clean(failpoints.status()));
                case "clear" -> {
                    failpoints.clear();
                    info("failpoint-clear", "status=CLEARED");
                }
                case "release" -> {
                    if (args.length != 3 || !SAFE_NONCE.matcher(args[2]).matches()) {
                        sender.sendMessage("Usage: /wtpscenario failpoint release <nonce>");
                        return;
                    }
                    failpoints.release(args[2]);
                    info("failpoint-release", "status=RELEASED nonce=" + args[2]);
                }
                case "arm" -> armFailpoint(sender, args);
                default -> sender.sendMessage(
                        "Usage: /wtpscenario failpoint <list|status|arm|clear|release>");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("failpoint-" + action, failureDetail(exception));
        }
    }

    private void armFailpoint(CommandSender sender, String[] args)
            throws ReflectiveOperationException {
        if (args.length < 4 || args.length > 6) {
            sender.sendMessage(
                    "Usage: /wtpscenario failpoint arm <point> <HALT|THROW|BLOCK> [occurrence] [nonce]");
            return;
        }
        String point = args[2].toLowerCase(Locale.ROOT);
        String action = args[3].toUpperCase(Locale.ROOT);
        if (!SAFE_POINT.matcher(point).matches() || !KNOWN_FAILPOINTS.contains(point)) {
            sender.sendMessage("Unknown failpoint. Use /wtpscenario failpoint list.");
            return;
        }
        if (!FAILPOINT_ACTIONS.contains(action)) {
            sender.sendMessage("Action must be HALT, THROW or BLOCK.");
            return;
        }
        int occurrence = 1;
        if (args.length >= 5) {
            try {
                occurrence = Integer.parseInt(args[4]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("Occurrence must be a positive integer.");
                return;
            }
        }
        if (occurrence < 1 || occurrence > 10_000) {
            sender.sendMessage("Occurrence must be between 1 and 10000.");
            return;
        }
        String nonce = args.length == 6 ? args[5] : UUID.randomUUID().toString();
        if (!SAFE_NONCE.matcher(nonce).matches()) {
            sender.sendMessage("Nonce must contain 8-128 letters, digits, dots, underscores or dashes.");
            return;
        }
        failpoints.arm(point, action, occurrence, nonce);
        info(
                "failpoint-arm",
                "status=ARMED point=" + point
                        + " action=" + action
                        + " occurrence=" + occurrence
                        + " nonce=" + nonce);
    }

    private void restartCommand(String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "prepare" -> prepareRestartMarker();
            case "clear" -> runMarkerIo("restart-marker-clear", () -> {
                restartMarkers.clear();
                return "status=CLEARED";
            });
            case "status" -> runMarkerIo(
                    "restart-marker-status",
                    () -> "status=" + (restartMarkers.exists() ? "PREPARED" : "NONE"));
            default -> info("restart-marker", "status=PENDING usage=restart-prepare-status-clear");
        }
    }

    private void prepareRestartMarker() {
        String targetVersion = target.getPluginMeta().getVersion();
        runMarkerIo("restart-marker-prepare", () -> {
            RestartMarkerStore.Marker marker = restartMarkers.prepare(targetVersion);
            return "status=PREPARED nonce=" + marker.nonce();
        });
    }

    private void inspectRestartMarker() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Optional<RestartMarkerStore.Marker> consumed = restartMarkers.consume();
                runOnMain(() -> {
                    if (consumed.isEmpty()) {
                        info("restart-marker", "status=NONE");
                        return;
                    }
                    RestartMarkerStore.Marker marker = consumed.orElseThrow();
                    String currentVersion = target.getPluginMeta().getVersion();
                    if (marker.targetVersion().equals(currentVersion)) {
                        pass(
                                "restart-marker",
                                "status=CONSUMED same-target-version=true nonce=" + marker.nonce());
                    } else {
                        fail(
                                "restart-marker",
                                "target-version-mismatch expected="
                                        + clean(marker.targetVersion())
                                        + " actual="
                                        + clean(currentVersion));
                    }
                });
            } catch (Exception exception) {
                runOnMain(() -> fail("restart-marker", failureDetail(exception)));
            }
        });
    }

    private void runMarkerIo(String scenario, MarkerIo operation) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String result = operation.run();
                runOnMain(() -> info(scenario, result));
            } catch (Exception exception) {
                runOnMain(() -> fail(scenario, failureDetail(exception)));
            }
        });
    }

    private void teleportCommand(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("status")) {
            info("teleport-status", "armed=" + teleportProbes.size());
            return;
        }
        if (action.equals("clear")) {
            teleportProbes.clear();
            info("teleport-clear", "status=CLEARED");
            return;
        }
        if (!action.equals("cancel") && !action.equals("retarget")) {
            sender.sendMessage("Usage: /wtpscenario teleport <cancel|retarget|status|clear> [player]");
            return;
        }
        Player player = resolvePlayer(sender, args, 2);
        if (player == null) {
            pending("teleport-" + action, "requires a real online player");
            return;
        }
        WalkThePlankApi api = api();
        if (api == null || api.activeRun(player.getUniqueId()).isEmpty()) {
            pending("teleport-" + action, "player must first enter an active WalkThePlank run");
            return;
        }

        TeleportMode mode =
                action.equals("cancel") ? TeleportMode.CANCEL : TeleportMode.RETARGET;
        Location original = player.getLocation().clone();
        TeleportProbe probe = new TeleportProbe(mode, original);
        teleportProbes.put(player.getUniqueId(), probe);
        info("teleport-" + action, "status=ARMED real-player=true");
        Location requested = original.clone().add(0.0, 1.0, 0.0);
        boolean accepted =
                player.teleport(requested, PlayerTeleportEvent.TeleportCause.PLUGIN);
        probe.teleportCallAccepted = accepted;
        if (!probe.intercepted) {
            teleportProbes.remove(player.getUniqueId(), probe);
            fail("teleport-" + action, "no real PlayerTeleportEvent was intercepted");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleportInterception(PlayerTeleportEvent event) {
        TeleportProbe probe = teleportProbes.get(event.getPlayer().getUniqueId());
        if (probe == null || probe.intercepted) {
            return;
        }
        probe.intercepted = true;
        probe.priorCancelled = event.isCancelled();
        if (probe.priorCancelled) {
            return;
        }
        if (probe.mode == TeleportMode.CANCEL) {
            event.setCancelled(true);
        } else {
            event.setTo(probe.retarget.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleportObservation(PlayerTeleportEvent event) {
        TeleportProbe probe = teleportProbes.remove(event.getPlayer().getUniqueId());
        if (probe == null || !probe.intercepted) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        if (probe.priorCancelled) {
            fail("teleport-" + probe.mode.id, "event was already cancelled before harness interception");
            return;
        }
        probe.finalCancelled = event.isCancelled();
        probe.finalDestinationRetargeted = samePosition(event.getTo(), probe.retarget);
        getServer().getScheduler().runTaskLater(
                this,
                () -> verifyTeleportOutcome(playerId, probe),
                3L);
    }

    private void verifyTeleportOutcome(UUID playerId, TeleportProbe probe) {
        WalkThePlankApi current = api();
        if (probe.mode == TeleportMode.CANCEL) {
            boolean retained =
                    current != null && current.activeRun(playerId).isPresent();
            if (probe.finalCancelled && !probe.teleportCallAccepted && retained) {
                pass(
                        "teleport-cancel",
                        "event-cancelled=true teleport-returned=false active-run-retained=true");
            } else {
                fail(
                        "teleport-cancel",
                        "event-cancelled=" + probe.finalCancelled
                                + " teleport-returned=" + probe.teleportCallAccepted
                                + " active-run-retained=" + retained);
            }
            return;
        }

        boolean ended = current != null && current.activeRun(playerId).isEmpty();
        boolean reasonRecorded =
                lastEndReasons.get(playerId) == SessionEndReason.TELEPORT;
        if (!probe.finalCancelled
                && probe.teleportCallAccepted
                && probe.finalDestinationRetargeted
                && ended
                && reasonRecorded) {
            pass(
                    "teleport-retarget",
                    "final-destination-retargeted=true active-run-ended=true reason=TELEPORT");
        } else {
            fail(
                    "teleport-retarget",
                    "cancelled=" + probe.finalCancelled
                            + " teleport-returned=" + probe.teleportCallAccepted
                            + " final-destination-retargeted="
                            + probe.finalDestinationRetargeted
                            + " active-run-ended=" + ended
                            + " reason-recorded=" + reasonRecorded);
        }
    }

    private void guiCommand(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("clear")) {
            guiProbes.clear();
            info("gui-clear", "status=CLEARED");
            return;
        }
        Player player = resolvePlayer(sender, args, 2);
        if (player == null) {
            pending("gui-" + action, "requires a real online player");
            return;
        }
        switch (action) {
            case "begin" -> beginGuiCapture(player);
            case "stale" -> rotateGuiSession(player);
            case "status" -> showGuiStatus(player);
            default -> sender.sendMessage("Usage: /wtpscenario gui <begin|status|stale|clear> [player]");
        }
    }

    private void beginGuiCapture(Player player) {
        boolean dispatched = getServer().dispatchCommand(player, "walk");
        if (!dispatched) {
            fail("gui-open", "player command /walk was not accepted");
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!isTargetMenu(top)) {
                fail("gui-open", "real client is not viewing a WalkThePlank menu");
                return;
            }
            GuiProbe probe = new GuiProbe(top);
            guiProbes.put(player.getUniqueId(), probe);
            pass("gui-open", "exact-inventory-captured=true");
            pending(
                    "gui-adversarial",
                    "use filler slots for left click, shift click, number key, double click, creative click and drag");
        }, 3L);
    }

    private void rotateGuiSession(Player player) {
        GuiProbe current = guiProbes.get(player.getUniqueId());
        if (current == null) {
            pending("gui-stale", "run /wtpscenario gui begin first");
            return;
        }
        Inventory oldInventory = current.inventory;
        getServer().getScheduler().runTaskLater(
                this,
                () -> runStaleGuiCallbackProbe(player, oldInventory),
                20L);
    }

    private void runStaleGuiCallbackProbe(Player player, Inventory oldInventory) {
        Map<Integer, Consumer<Player>> actions = null;
        Consumer<Player> originalAction = null;
        boolean callbackScheduled = false;
        try {
            if (!player.isOnline()
                    || player.getOpenInventory().getTopInventory() != oldInventory
                    || !isTargetMenu(oldInventory)) {
                fail("gui-stale", "captured menu is no longer the authoritative open view");
                return;
            }
            ClassLoader targetLoader = target.getClass().getClassLoader();
            Field menusField = requireAccessibleField(target.getClass(), "menus");
            Object menus = menusField.get(target);
            requireTargetClass(menus, "com.mrfdev.walktheplank.gui.MenuService", targetLoader);

            Field sessionsField = requireAccessibleField(menus.getClass(), "sessions");
            Object sessions = sessionsField.get(menus);
            requireTargetClass(
                    sessions,
                    "com.mrfdev.walktheplank.gui.GuiSessionRegistry",
                    targetLoader);
            Method currentMethod =
                    requireAccessibleMethod(sessions.getClass(), "current", UUID.class);
            Object oldSession = currentMethod.invoke(sessions, player.getUniqueId());
            if (oldSession == null) {
                throw new IllegalStateException("Current GUI session is missing");
            }
            requireTargetClass(
                    oldSession,
                    "com.mrfdev.walktheplank.gui.GuiSessionRegistry$Session",
                    targetLoader);
            Object oldPage = invokeNoArgs(oldSession, "page");
            requireTargetClass(oldPage, MENU_HOLDER_CLASS, targetLoader);
            if (invokeNoArgs(oldSession, "inventory") != oldInventory) {
                throw new IllegalStateException("Captured inventory does not match the session");
            }

            Field actionsField = requireAccessibleField(oldPage.getClass(), "actions");
            actions = requireActionMap(actionsField.get(oldPage));
            originalAction = requirePlayerAction(actions.get(13));
            AtomicBoolean sentinelExecuted = new AtomicBoolean();
            Consumer<Player> sentinel = ignored -> sentinelExecuted.set(true);
            actions.put(13, sentinel);

            Method scheduleAction = requireAccessibleMethod(
                    menus.getClass(),
                    "scheduleAction",
                    Player.class,
                    oldSession.getClass(),
                    int.class,
                    Consumer.class,
                    ClickType.class);
            scheduleAction.invoke(
                    menus,
                    player,
                    oldSession,
                    13,
                    sentinel,
                    ClickType.LEFT);
            callbackScheduled = true;
            if (pendingGuiActions(menus) != 1) {
                throw new IllegalStateException(
                        "The production GUI gate did not queue exactly one callback");
            }

            Method open = menus.getClass().getMethod("open", Player.class);
            open.invoke(menus, player);
            Object replacementSession =
                    currentMethod.invoke(sessions, player.getUniqueId());
            if (replacementSession == null || replacementSession == oldSession) {
                throw new IllegalStateException("Replacement GUI session was not activated");
            }
            Inventory replacement = requireInventory(
                    invokeNoArgs(replacementSession, "inventory"));
            UUID oldNonce = requireUuid(invokeNoArgs(oldSession, "nonce"));
            UUID replacementNonce = requireUuid(invokeNoArgs(replacementSession, "nonce"));
            long oldGeneration = requireLong(invokeNoArgs(oldSession, "generation"));
            long replacementGeneration =
                    requireLong(invokeNoArgs(replacementSession, "generation"));
            boolean identityRotated = replacement != oldInventory
                    && !replacementNonce.equals(oldNonce)
                    && replacementGeneration > oldGeneration
                    && player.getOpenInventory().getTopInventory() == replacement
                    && isTargetMenu(replacement)
                    && pendingGuiActions(menus) == 0;
            if (!identityRotated) {
                throw new IllegalStateException(
                        "Nonce, generation, inventory, view, or pending state did not rotate");
            }

            guiProbes.put(player.getUniqueId(), new GuiProbe(replacement));
            pass(
                    "gui-stale-identity",
                    "nonce-generation-exact-inventory-rotated=true pending-actions=0");
            AtomicBoolean targetSchedulerWitness = new AtomicBoolean();
            getServer().getScheduler().runTask(
                    target,
                    () -> targetSchedulerWitness.set(true));
            Map<Integer, Consumer<Player>> capturedActions = actions;
            Consumer<Player> capturedOriginalAction = originalAction;
            getServer().getScheduler().runTaskLater(this, () -> {
                capturedActions.put(13, capturedOriginalAction);
                try {
                    Object authoritative =
                            currentMethod.invoke(sessions, player.getUniqueId());
                    boolean rejected = target.isEnabled()
                            && targetSchedulerWitness.get()
                            && !sentinelExecuted.get()
                            && authoritative == replacementSession
                            && player.isOnline()
                            && player.getOpenInventory().getTopInventory() == replacement
                            && pendingGuiActions(menus) == 0;
                    if (rejected) {
                        pass(
                                "gui-stale-action",
                                "real-scheduled-callback-rejected=true sentinel-executed=false");
                    } else {
                        fail(
                                "gui-stale-action",
                                "callback-rejected=" + !sentinelExecuted.get()
                                        + " scheduler-witness="
                                        + targetSchedulerWitness.get()
                                        + " replacement-authoritative="
                                        + (authoritative == replacementSession));
                    }
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    fail("gui-stale-action", failureDetail(exception));
                }
            }, 2L);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (actions != null && originalAction != null) {
                Map<Integer, Consumer<Player>> capturedActions = actions;
                Consumer<Player> capturedOriginalAction = originalAction;
                if (callbackScheduled) {
                    player.closeInventory();
                    getServer().getScheduler().runTaskLater(
                            this,
                            () -> capturedActions.put(13, capturedOriginalAction),
                            2L);
                } else {
                    actions.put(13, originalAction);
                }
            }
            fail("gui-stale", failureDetail(exception));
        }
    }

    private void showGuiStatus(Player player) {
        GuiProbe probe = guiProbes.get(player.getUniqueId());
        if (probe == null) {
            pending("gui-status", "no real-client capture is armed");
            return;
        }
        EnumSet<GuiGesture> missing = EnumSet.allOf(GuiGesture.class);
        missing.removeAll(probe.observed);
        info(
                "gui-status",
                "observed=" + enumNames(probe.observed)
                        + " missing=" + enumNames(missing)
                        + " complete=" + probe.completed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuiClickObservation(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiProbe probe = guiProbes.get(player.getUniqueId());
        if (probe == null || event.getView().getTopInventory() != probe.inventory) {
            return;
        }
        GuiGesture gesture = guiGesture(event.getClick());
        if (gesture == null || probe.observed.contains(gesture)) {
            return;
        }
        recordGuiGesture(probe, gesture, event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuiDragObservation(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiProbe probe = guiProbes.get(player.getUniqueId());
        if (probe == null
                || event.getView().getTopInventory() != probe.inventory
                || probe.observed.contains(GuiGesture.DRAG)) {
            return;
        }
        boolean touchesTop =
                event.getRawSlots().stream().anyMatch(slot -> slot < probe.inventory.getSize());
        if (touchesTop) {
            recordGuiGesture(probe, GuiGesture.DRAG, event.isCancelled());
        }
    }

    private void recordGuiGesture(GuiProbe probe, GuiGesture gesture, boolean cancelled) {
        if (!cancelled) {
            fail("gui-" + gesture.id, "real-client event was not cancelled");
            return;
        }
        probe.observed.add(gesture);
        pass("gui-" + gesture.id, "real-client-event-cancelled=true");
        if (!probe.completed
                && probe.observed.size() == GuiGesture.values().length) {
            probe.completed = true;
            pass("gui-adversarial", "real-client-cases=6/6");
        }
    }

    private void reconnectCommand(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("clear")) {
            reconnectProbes.clear();
            info("reconnect-clear", "status=CLEARED");
            return;
        }
        Player player = resolvePlayer(sender, args, 2);
        if (player == null) {
            pending("reconnect-" + action, "requires a real online player");
            return;
        }
        if (action.equals("status")) {
            info(
                    "reconnect-status",
                    "armed=" + reconnectProbes.containsKey(player.getUniqueId()));
            return;
        }
        if (!action.equals("arm")) {
            sender.sendMessage("Usage: /wtpscenario reconnect <arm|status|clear> [player]");
            return;
        }
        WalkThePlankApi current = api();
        if (current == null || current.activeRun(player.getUniqueId()).isEmpty()) {
            pending("reconnect", "player must first enter an active WalkThePlank run");
            return;
        }
        reconnectProbes.put(player.getUniqueId(), new ReconnectProbe());
        info("reconnect", "status=ARMED disconnect-and-rejoin=true");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        ReconnectProbe reconnect = reconnectProbes.get(playerId);
        if (reconnect != null) {
            reconnect.quitObserved = true;
            info(
                    "reconnect",
                    "status=WAITING_FOR_JOIN quit-reason-recorded="
                            + (lastEndReasons.get(playerId) == SessionEndReason.QUIT));
        }
        if (teleportProbes.remove(playerId) != null) {
            pending("teleport", "player disconnected while teleport probe was armed");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (guiProbes.remove(playerId) != null) {
            info("gui-reconnect", "status=PENDING reopen menu and repeat unfinished gestures");
        }
        ReconnectProbe reconnect = reconnectProbes.get(playerId);
        if (reconnect == null || !reconnect.quitObserved) {
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            WalkThePlankApi current = api();
            boolean noActiveRun =
                    current != null && current.activeRun(playerId).isEmpty();
            boolean quitReason =
                    lastEndReasons.get(playerId) == SessionEndReason.QUIT;
            reconnectProbes.remove(playerId, reconnect);
            if (noActiveRun && quitReason) {
                pass("reconnect-run-state", "active-run-cleared=true reason=QUIT");
            } else {
                fail(
                        "reconnect-run-state",
                        "active-run-cleared=" + noActiveRun
                                + " reason-quit=" + quitReason);
            }
            pending(
                    "reconnect-player-state",
                    "verify return location, inventory, game mode, flight and attributes in the real client");
        }, 40L);
    }

    private void exitsCommand(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("clear")) {
            observedEndReasons.clear();
            lastEndReasons.clear();
            expectedEndReasons.clear();
            info("run-end-reasons", "status=CLEARED");
            return;
        }
        if (action.equals("status")) {
            List<String> missing = EnumSet.allOf(SessionEndReason.class).stream()
                    .filter(reason -> observedEndReasons.getOrDefault(reason, 0) == 0)
                    .map(Enum::name)
                    .toList();
            info(
                    "run-end-reasons",
                    "observed=" + reasonCounts()
                            + " missing=" + String.join(",", missing)
                            + " status=" + (missing.isEmpty() ? "COMPLETE" : "PENDING"));
            return;
        }
        if (!action.equals("expect") || args.length < 3) {
            sender.sendMessage("Usage: /wtpscenario exits <expect <reason>|status|clear>");
            return;
        }
        Player player = resolvePlayer(sender, args, 3);
        if (player == null) {
            pending("run-end-expect", "requires a real online player");
            return;
        }
        SessionEndReason expected;
        try {
            expected = SessionEndReason.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("Unknown reason: " + args[2]);
            return;
        }
        expectedEndReasons.put(player.getUniqueId(), expected);
        info("run-end-expect", "status=ARMED reason=" + expected);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRunStart(WalkRunStartEvent event) {
        info("run-start-event", "arena-present=" + !event.arenaId().isBlank());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRunEnd(WalkRunEndEvent event) {
        UUID playerId = event.player().getUniqueId();
        observedEndReasons.merge(event.reason(), 1, Integer::sum);
        lastEndReasons.put(playerId, event.reason());
        info(
                "run-end-event",
                "reason=" + event.reason()
                        + " cleanup-complete=" + event.cleanupComplete()
                        + " count=" + observedEndReasons.get(event.reason()));
        SessionEndReason expected = expectedEndReasons.remove(playerId);
        if (expected == null) {
            return;
        }
        if (expected != event.reason()) {
            fail(
                    "run-end-" + expected.name().toLowerCase(Locale.ROOT),
                    "observed=" + event.reason());
        } else if (!event.cleanupComplete()) {
            fail(
                    "run-end-" + expected.name().toLowerCase(Locale.ROOT),
                    "reason-matched=true cleanup-complete=false");
        } else {
            pass(
                    "run-end-" + expected.name().toLowerCase(Locale.ROOT),
                    "reason-matched=true cleanup-complete=true");
        }
    }

    private void runContention() {
        if (contentionTask != null) {
            info("queue-contention", "status=ALREADY_RUNNING");
            return;
        }
        WalkThePlankApi current = api();
        if (current == null) {
            fail("queue-contention", "WalkThePlank API is unavailable");
            return;
        }
        List<? extends Player> eligible = getServer().getOnlinePlayers().stream()
                .filter(Player::isOnline)
                .filter(player -> current.activeRun(player.getUniqueId()).isEmpty())
                .filter(player -> {
                    WalkThePlankApi.QueueView queue = current.queue(player.getUniqueId());
                    return queue.position() == 0 && queue.readyUntil().isEmpty();
                })
                .sorted(Comparator.comparing(player -> player.getUniqueId().toString()))
                .limit(2)
                .toList();
        if (eligible.size() < 2) {
            pending(
                    "queue-contention",
                    "requires two online players who are not active, queued or ready");
            return;
        }
        Player first = eligible.get(0);
        Player second = eligible.get(1);
        boolean firstAccepted = getServer().dispatchCommand(first, "walk play");
        boolean secondAccepted = getServer().dispatchCommand(second, "walk play");
        if (!firstAccepted || !secondAccepted) {
            fail(
                    "queue-contention",
                    "same-tick-command-accepted="
                            + firstAccepted + "," + secondAccepted);
            return;
        }
        info("queue-contention", "status=WAITING same-tick-dispatches=2");
        contentionTask = new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                elapsedTicks += 5;
                WalkThePlankApi api = api();
                if (api == null || !first.isOnline() || !second.isOnline()) {
                    finishContention(this);
                    fail("queue-contention", "API or a real player became unavailable");
                    return;
                }
                PlayerParticipation firstState = participation(api, first.getUniqueId());
                PlayerParticipation secondState = participation(api, second.getUniqueId());
                if (firstState.participating && secondState.participating) {
                    finishContention(this);
                    verifyContentionOutcome(api, firstState, secondState);
                    return;
                }
                if (elapsedTicks >= 100) {
                    finishContention(this);
                    fail(
                            "queue-contention",
                            "timed-out first=" + firstState.label
                                    + " second=" + secondState.label);
                }
            }
        }.runTaskTimer(this, 5L, 5L);
    }

    private void verifyContentionOutcome(
            WalkThePlankApi api,
            PlayerParticipation first,
            PlayerParticipation second) {
        WalkThePlankApi.Capacity capacity = api.capacity();
        boolean capacitySafe = capacity.active() <= capacity.configured();
        boolean distinctArenas = first.arenaId.isEmpty()
                || second.arenaId.isEmpty()
                || !first.arenaId.equals(second.arenaId);
        if (!capacitySafe || !distinctArenas) {
            fail(
                    "queue-contention",
                    "capacity-safe=" + capacitySafe
                            + " distinct-arenas=" + distinctArenas);
            return;
        }
        pass(
                "queue-contention",
                "same-tick-dispatches=2 outcomes=" + first.label + "+" + second.label
                        + " capacity-safe=true");
    }

    private void finishContention(BukkitRunnable runnable) {
        runnable.cancel();
        contentionTask = null;
    }

    private void cancelContentionTask() {
        if (contentionTask != null) {
            contentionTask.cancel();
            contentionTask = null;
        }
    }

    private PlayerParticipation participation(WalkThePlankApi api, UUID playerId) {
        Optional<WalkThePlankApi.ActiveRun> run = api.activeRun(playerId);
        if (run.isPresent()) {
            return new PlayerParticipation(true, "active", run.orElseThrow().arenaId());
        }
        WalkThePlankApi.QueueView queue = api.queue(playerId);
        if (queue.position() > 0) {
            return new PlayerParticipation(true, "queued", "");
        }
        if (queue.readyUntil().isPresent()) {
            return new PlayerParticipation(true, "ready", "");
        }
        return new PlayerParticipation(false, "none", "");
    }

    private void showStatus(CommandSender sender) {
        WalkThePlankApi current = api();
        String bridgeStatus;
        try {
            bridgeStatus = failpoints == null ? "unavailable" : clean(failpoints.status());
        } catch (ReflectiveOperationException exception) {
            bridgeStatus = "error-" + exception.getClass().getSimpleName();
        }
        String status = "passes=" + passCount
                + " failures=" + failureCount
                + " target-enabled=" + (target != null && target.isEnabled())
                + " api=" + (current != null)
                + " bridge=" + bridgeStatus
                + " gui-probes=" + guiProbes.size()
                + " teleport-probes=" + teleportProbes.size()
                + " reconnect-probes=" + reconnectProbes.size();
        sender.sendMessage(status);
        info("status", status);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("/wtpscenario run <headless|lifecycle|player|queue>");
        sender.sendMessage("/wtpscenario failpoint <list|status|arm|clear|release>");
        sender.sendMessage("/wtpscenario restart <prepare|status|clear>");
        sender.sendMessage("/wtpscenario teleport <cancel|retarget|status|clear>");
        sender.sendMessage("/wtpscenario gui <begin|status|stale|clear>");
        sender.sendMessage("/wtpscenario reconnect <arm|status|clear>");
        sender.sendMessage("/wtpscenario exits <expect <reason>|status|clear>");
        sender.sendMessage("/wtpscenario contention");
    }

    private boolean verifyApi(String scenario) {
        WalkThePlankApi current = api();
        if (current == null) {
            fail(scenario, "service is not registered");
            return false;
        }
        String release;
        try {
            release = current.release();
        } catch (RuntimeException | LinkageError exception) {
            fail(scenario, failureDetail(exception));
            return false;
        }
        if (release == null || release.isBlank()) {
            fail(scenario, "service returned a blank release");
            return false;
        }
        pass(scenario, "service-registered=true release=" + clean(release));
        return true;
    }

    private WalkThePlankApi api() {
        return getServer().getServicesManager().load(WalkThePlankApi.class);
    }

    private boolean verifyPlaceholderExpectation() {
        String expected =
                System.getProperty(PLACEHOLDER_PROPERTY, "").toLowerCase(Locale.ROOT);
        if (!expected.equals("present") && !expected.equals("absent")) {
            fail(
                    "placeholderapi",
                    "set -D" + PLACEHOLDER_PROPERTY + "=present or absent");
            return false;
        }
        Plugin papi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        boolean installed = papi != null;
        boolean enabled = installed && papi.isEnabled();
        if (expected.equals("absent")) {
            boolean classVisible = classVisible(PAPI_CLASS, target.getClass().getClassLoader());
            if (installed || classVisible) {
                fail(
                        "placeholderapi-absent",
                        "installed=" + installed + " target-class-visible=" + classVisible);
                return false;
            }
            pass("placeholderapi-absent", "installed=false target-class-visible=false");
            return true;
        }
        if (!enabled) {
            fail("placeholderapi-present", "plugin is missing or disabled");
            return false;
        }
        try {
            Class<?> placeholderApi =
                    Class.forName(PAPI_CLASS, true, papi.getClass().getClassLoader());
            Method isRegistered = placeholderApi.getMethod("isRegistered", String.class);
            Object result = isRegistered.invoke(null, PAPI_IDENTIFIER);
            if (!Boolean.TRUE.equals(result)) {
                fail("placeholderapi-present", "infinityparkour expansion is not registered");
                return false;
            }
            pass("placeholderapi-present", "enabled=true expansion-registered=true");
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("placeholderapi-present", failureDetail(exception));
            return false;
        }
    }

    private Player resolvePlayer(CommandSender sender, String[] args, int nameIndex) {
        if (args.length > nameIndex) {
            return getServer().getPlayerExact(args[nameIndex]);
        }
        return sender instanceof Player player ? player : null;
    }

    private boolean isTargetMenu(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        return holder != null
                && MENU_HOLDER_CLASS.equals(holder.getClass().getName())
                && holder.getClass().getClassLoader() == target.getClass().getClassLoader();
    }

    private static Field requireAccessibleField(Class<?> type, String name)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) {
            throw new IllegalAccessException("Required scenario field is inaccessible");
        }
        return field;
    }

    private static Method requireAccessibleMethod(
            Class<?> type,
            String name,
            Class<?>... parameterTypes) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        if (!method.trySetAccessible()) {
            throw new IllegalAccessException("Required scenario method is inaccessible");
        }
        return method;
    }

    private static Object invokeNoArgs(Object target, String methodName)
            throws ReflectiveOperationException {
        return requireAccessibleMethod(target.getClass(), methodName).invoke(target);
    }

    private static void requireTargetClass(
            Object value,
            String className,
            ClassLoader targetLoader) {
        if (value == null
                || !className.equals(value.getClass().getName())
                || value.getClass().getClassLoader() != targetLoader) {
            throw new IllegalStateException("Required target-owned scenario object is missing");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Consumer<Player>> requireActionMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("GUI action map is missing");
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof Integer)
                    || !(entry.getValue() instanceof Consumer<?>)) {
                throw new IllegalStateException("GUI action map has an unexpected shape");
            }
        }
        return (Map<Integer, Consumer<Player>>) map;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Player> requirePlayerAction(Object value) {
        if (!(value instanceof Consumer<?> action)) {
            throw new IllegalStateException("Expected GUI action is missing");
        }
        return (Consumer<Player>) action;
    }

    private static Inventory requireInventory(Object value) {
        if (!(value instanceof Inventory inventory)) {
            throw new IllegalStateException("GUI session inventory is missing");
        }
        return inventory;
    }

    private static UUID requireUuid(Object value) {
        if (!(value instanceof UUID uuid)) {
            throw new IllegalStateException("GUI session nonce is missing");
        }
        return uuid;
    }

    private static long requireLong(Object value) {
        if (!(value instanceof Long number)) {
            throw new IllegalStateException("GUI session generation is missing");
        }
        return number;
    }

    private static int pendingGuiActions(Object menus)
            throws ReflectiveOperationException {
        Object health = menus.getClass().getMethod("health").invoke(menus);
        Object pending = health.getClass().getMethod("pendingActions").invoke(health);
        if (!(pending instanceof Integer count)) {
            throw new IllegalStateException("GUI pending-action health is unavailable");
        }
        return count;
    }

    private static GuiGesture guiGesture(ClickType click) {
        return switch (click) {
            case LEFT -> GuiGesture.CLICK;
            case SHIFT_LEFT, SHIFT_RIGHT -> GuiGesture.SHIFT;
            case NUMBER_KEY -> GuiGesture.HOTBAR;
            case DOUBLE_CLICK -> GuiGesture.DOUBLE;
            case CREATIVE, MIDDLE -> GuiGesture.CREATIVE;
            default -> null;
        };
    }

    private static boolean samePosition(Location first, Location second) {
        if (first == null
                || first.getWorld() == null
                || second.getWorld() == null
                || !first.getWorld().getUID().equals(second.getWorld().getUID())) {
            return false;
        }
        return Math.abs(first.getX() - second.getX()) <= 1.0E-6
                && Math.abs(first.getY() - second.getY()) <= 1.0E-6
                && Math.abs(first.getZ() - second.getZ()) <= 1.0E-6;
    }

    private static boolean classVisible(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private static Path requireDisposableProfileRoot() throws IOException {
        if (!"true".equalsIgnoreCase(System.getProperty(PROFILE_PROPERTY, ""))) {
            throw new SecurityException("Disposable scenario profile flag is missing");
        }
        String configuredRoot = System.getProperty(PROFILE_ROOT_PROPERTY);
        String nonce = System.getProperty(PROFILE_NONCE_PROPERTY);
        if (configuredRoot == null
                || configuredRoot.isBlank()
                || nonce == null
                || !SAFE_NONCE.matcher(nonce).matches()) {
            throw new SecurityException("Disposable scenario root or nonce is invalid");
        }
        Path requestedRoot = Path.of(configuredRoot);
        Path normalizedRoot = requestedRoot.toAbsolutePath().normalize();
        if (!requestedRoot.isAbsolute() || !requestedRoot.equals(normalizedRoot)) {
            throw new SecurityException(
                    "Disposable scenario root must be an absolute normalized path");
        }
        Path realRoot = normalizedRoot.toRealPath();
        if (!normalizedRoot.equals(realRoot)
                || Files.isSymbolicLink(normalizedRoot)
                || !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
                || !realRoot.equals(Path.of("").toAbsolutePath().normalize().toRealPath())
                || !hasDisposableLayout(realRoot)) {
            throw new SecurityException(
                    "Disposable scenario root does not match the generated server profile");
        }
        Path marker = realRoot.resolve(PROFILE_MARKER);
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.size(marker) > 256
                || !Files.readString(marker, StandardCharsets.UTF_8)
                        .equals("schema=1\nnonce=" + nonce + "\n")) {
            throw new SecurityException("Disposable scenario marker is invalid");
        }
        return realRoot;
    }

    private static boolean hasDisposableLayout(Path root) {
        Path profileName = root.getFileName();
        Path controlledRoot = root.getParent();
        Path buildRoot = controlledRoot == null ? null : controlledRoot.getParent();
        if (profileName == null
                || controlledRoot == null
                || buildRoot == null
                || controlledRoot.getFileName() == null
                || buildRoot.getFileName() == null
                || !"controlled-scenarios".equals(controlledRoot.getFileName().toString())
                || !"build".equals(buildRoot.getFileName().toString())) {
            return false;
        }
        String profile = profileName.toString();
        return "two-start".equals(profile)
                || "player-assisted".equals(profile)
                || profile.startsWith("failpoint-");
    }

    private static void requireExactDataPath(Path actual, Path expected) throws IOException {
        Path normalizedActual = actual.toAbsolutePath().normalize();
        Path normalizedExpected = expected.toAbsolutePath().normalize();
        Path actualParent = Objects.requireNonNull(normalizedActual.getParent()).toRealPath();
        Path expectedParent = Objects.requireNonNull(normalizedExpected.getParent()).toRealPath();
        if (!normalizedActual.equals(normalizedExpected)
                || Files.isSymbolicLink(normalizedActual)
                || !actualParent.equals(expectedParent)
                || (Files.exists(normalizedActual, LinkOption.NOFOLLOW_LINKS)
                        && !normalizedActual.toRealPath().equals(normalizedExpected.toRealPath()))) {
            throw new SecurityException("Plugin data path escaped the disposable profile");
        }
    }

    private void runOnMain(Runnable action) {
        if (!isEnabled()) {
            return;
        }
        getServer().getScheduler().runTask(this, action);
    }

    private void failStartup(String scenario, String detail) {
        fail(scenario, detail);
        getServer().getPluginManager().disablePlugin(this);
    }

    private void pass(String scenario, String detail) {
        passCount++;
        getLogger().info("WTP-SCENARIO PASS " + scenario + " " + clean(detail));
    }

    private void fail(String scenario, String detail) {
        failureCount++;
        getLogger().severe("WTP-SCENARIO FAIL " + scenario + " " + clean(detail));
    }

    private void info(String scenario, String detail) {
        getLogger().info("WTP-SCENARIO INFO " + scenario + " " + clean(detail));
    }

    private void pending(String scenario, String detail) {
        info(scenario, "status=PENDING " + detail);
    }

    private static String failureDetail(Throwable failure) {
        Throwable useful = failure;
        if (failure instanceof InvocationTargetException targetFailure
                && targetFailure.getCause() != null) {
            useful = targetFailure.getCause();
        }
        String message = useful.getMessage();
        return useful.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : "-" + clean(message));
    }

    private static String clean(String value) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll(" +", " ")
                .trim();
        return normalized.length() <= 480
                ? normalized
                : normalized.substring(0, 480) + "...";
    }

    private static List<String> matching(String prefix, List<String> choices) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }

    private String reasonCounts() {
        List<String> counts = EnumSet.allOf(SessionEndReason.class).stream()
                .filter(reason -> observedEndReasons.getOrDefault(reason, 0) > 0)
                .map(reason -> reason.name() + ":" + observedEndReasons.get(reason))
                .toList();
        return counts.isEmpty() ? "none" : String.join(",", counts);
    }

    private static String enumNames(Iterable<? extends Enum<?>> values) {
        ArrayList<String> names = new ArrayList<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names.isEmpty() ? "none" : String.join(",", names);
    }

    @FunctionalInterface
    private interface MarkerIo {
        String run() throws Exception;
    }

    private enum TeleportMode {
        CANCEL("cancel"),
        RETARGET("retarget");

        private final String id;

        TeleportMode(String id) {
            this.id = id;
        }
    }

    private enum GuiGesture {
        CLICK("click"),
        SHIFT("shift"),
        HOTBAR("hotbar"),
        DOUBLE("double"),
        CREATIVE("creative"),
        DRAG("drag");

        private final String id;

        GuiGesture(String id) {
            this.id = id;
        }
    }

    private static final class TeleportProbe {
        private final TeleportMode mode;
        private final Location retarget;
        private boolean intercepted;
        private boolean priorCancelled;
        private boolean teleportCallAccepted;
        private boolean finalCancelled;
        private boolean finalDestinationRetargeted;

        private TeleportProbe(TeleportMode mode, Location retarget) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.retarget = Objects.requireNonNull(retarget, "retarget");
        }
    }

    private static final class GuiProbe {
        private final Inventory inventory;
        private final EnumSet<GuiGesture> observed = EnumSet.noneOf(GuiGesture.class);
        private boolean completed;

        private GuiProbe(Inventory inventory) {
            this.inventory = Objects.requireNonNull(inventory, "inventory");
        }
    }

    private static final class ReconnectProbe {
        private boolean quitObserved;
    }

    private record PlayerParticipation(
            boolean participating,
            String label,
            String arenaId) {
    }
}
