package com.mrfdev.walktheplank.gui;

import com.mrfdev.walktheplank.config.ConfigurationManager;
import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.PlayerStats;
import com.mrfdev.walktheplank.database.ScoreEntry;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.game.GameManager;
import com.mrfdev.walktheplank.security.ActionRateLimiter;
import com.mrfdev.walktheplank.text.MessageService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class MenuService {
    private static final Duration MENU_OPEN_COOLDOWN = Duration.ofMillis(750);
    private static final String SERVER_MENU_COMMAND = "menu";

    private final JavaPlugin plugin;
    private final ConfigurationManager configuration;
    private final Supplier<RuntimeSettings> settings;
    private final ScoreRepository scores;
    private final GameManager games;
    private final MessageService messages;
    private final ItemFactory items;
    private final ActionRateLimiter rateLimiter = new ActionRateLimiter();
    private final GuiSessionRegistry<Inventory, ParkourMenu> sessions = new GuiSessionRegistry<>();
    private final GuiActionGate actionGate = new GuiActionGate();

    public MenuService(
            JavaPlugin plugin,
            ConfigurationManager configuration,
            Supplier<RuntimeSettings> settings,
            ScoreRepository scores,
            GameManager games,
            MessageService messages,
            ItemFactory items) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.games = Objects.requireNonNull(games, "games");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.items = Objects.requireNonNull(items, "items");
    }

    public void open(Player player) {
        Objects.requireNonNull(player, "player");
        if (!Bukkit.isPrimaryThread()) {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> open(player));
            } catch (RuntimeException | LinkageError schedulingFailure) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not schedule a WalkThePlank menu open on the main thread",
                        schedulingFailure);
            }
            return;
        }
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        if (!rateLimiter.tryAcquire(
                        player.getUniqueId(),
                        "menu.open",
                        MENU_OPEN_COOLDOWN,
                        Instant.now())
                .allowed()) {
            messages.send(player, "chat.slowDown");
            return;
        }

        ParkourMenu menu = new ParkourMenu(player);
        UUID ownerId = player.getUniqueId();
        GuiSessionRegistry.Session<Inventory, ParkourMenu> previous = sessions.current(ownerId);
        GuiSessionRegistry.Session<Inventory, ParkourMenu> current = sessions.activate(
                ownerId,
                menu.nonce(),
                menu.getInventory(),
                menu);
        menu.bindGeneration(current.generation());
        if (previous != null) {
            actionGate.invalidate(previous.key());
        }

        try {
            player.openInventory(menu.getInventory());
        } catch (RuntimeException | LinkageError openFailure) {
            invalidate(current);
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not open the WalkThePlank menu safely",
                    openFailure);
            try {
                player.sendMessage(messages.prefixedTemplate(
                        "&cThe menu could not be opened safely.", Map.of()));
            } catch (RuntimeException | LinkageError messageFailure) {
                openFailure.addSuppressed(messageFailure);
            }
            return;
        }
        if (!isCurrentView(player, current)) {
            invalidate(current);
        }
    }

    public void closeOpenMenus() {
        for (GuiSessionRegistry.Session<Inventory, ParkourMenu> session : sessions.snapshot()) {
            Player player = Bukkit.getPlayer(session.ownerId());
            boolean shouldClose = player != null && isCurrentView(player, session);
            invalidate(session);
            if (shouldClose) {
                try {
                    player.closeInventory();
                } catch (RuntimeException | LinkageError closeFailure) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not close a WalkThePlank menu during shutdown or reload",
                            closeFailure);
                }
            }
        }
        sessions.clear();
        actionGate.clear();
    }

    public void sendStats(Player player) {
        PlayerStats stats = scores.stats(player.getUniqueId()).orElse(null);
        if (stats == null) {
            messages.send(player, "chat.chatStatsError");
            return;
        }
        messages.send(player, "chat.chatStats", Map.of(
                "playerPlace", stats.rank(),
                "totalPlaces", stats.totalEntries(),
                "percentile", stats.percentile(),
                "playerScore", stats.bestScore()));
    }

    public Health health() {
        return new Health(sessions.size(), actionGate.pendingCount());
    }

    private void scheduleAction(
            Player player,
            GuiSessionRegistry.Session<Inventory, ParkourMenu> session,
            int slot,
            Consumer<Player> action,
            org.bukkit.event.inventory.ClickType clickType) {
        GuiActionGate.BeginResult result = actionGate.tryBegin(
                session.key(),
                slot,
                clickType,
                System.nanoTime());
        if (result != GuiActionGate.BeginResult.ACCEPTED) {
            return;
        }

        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> executeAction(player, session, slot, action));
        } catch (RuntimeException | LinkageError schedulingFailure) {
            actionGate.complete(session.key());
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not schedule a WalkThePlank GUI action",
                    schedulingFailure);
        }
    }

    private void executeAction(
            Player player,
            GuiSessionRegistry.Session<Inventory, ParkourMenu> session,
            int slot,
            Consumer<Player> action) {
        try {
            if (!isCurrentView(player, session)
                    || session.page().actionAt(slot) != action) {
                return;
            }
            /*
             * Actions obtain the latest permissions, queue state, arena
             * availability and score data inside accept(), immediately before
             * invoking the authoritative service method.
             */
            action.accept(player);
        } catch (RuntimeException | LinkageError actionFailure) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "WalkThePlank GUI action failed safely",
                    actionFailure);
        } finally {
            actionGate.complete(session.key());
        }
    }

    private GuiSessionRegistry.Session<Inventory, ParkourMenu> currentSession(
            Player player,
            ParkourMenu menu,
            Inventory topInventory) {
        if (menu.service() != this
                || !menu.ownerId().equals(player.getUniqueId())
                || menu.getInventory() != topInventory
                || !sessions.isCurrent(
                        menu.ownerId(),
                        menu.nonce(),
                        menu.generation(),
                        topInventory)) {
            return null;
        }
        GuiSessionRegistry.Session<Inventory, ParkourMenu> session =
                sessions.current(menu.ownerId());
        return session != null && session.page() == menu ? session : null;
    }

    private boolean isCurrentView(
            Player player,
            GuiSessionRegistry.Session<Inventory, ParkourMenu> session) {
        if (!player.isOnline()
                || !player.getUniqueId().equals(session.ownerId())
                || !sessions.isCurrent(session)) {
            return false;
        }
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        if (topInventory != session.inventory()) {
            return false;
        }
        return topInventory.getHolder(false) instanceof ParkourMenu menu
                && menu.service() == this
                && menu.ownerId().equals(session.ownerId())
                && menu.nonce().equals(session.nonce())
                && menu.generation() == session.generation()
                && menu.getInventory() == session.inventory()
                && session.page() == menu;
    }

    private void invalidate(GuiSessionRegistry.Session<Inventory, ParkourMenu> session) {
        sessions.removeIfCurrent(session);
        actionGate.invalidate(session.key());
    }

    private void invalidate(UUID playerId) {
        GuiSessionRegistry.Session<Inventory, ParkourMenu> session = sessions.remove(playerId);
        actionGate.invalidateOwner(playerId);
        rateLimiter.clear(playerId);
        if (session != null) {
            actionGate.invalidate(session.key());
        }
    }

    private void closeForLifecycle(Player player) {
        GuiSessionRegistry.Session<Inventory, ParkourMenu> session =
                sessions.current(player.getUniqueId());
        if (session == null) {
            return;
        }
        boolean shouldClose = isCurrentView(player, session);
        invalidate(session);
        if (shouldClose) {
            try {
                player.closeInventory();
            } catch (RuntimeException | LinkageError closeFailure) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not close a stale WalkThePlank menu",
                        closeFailure);
            }
        }
    }

    private final class ParkourMenu implements InventoryHolder {
        private final Player player;
        private final UUID ownerId;
        private final UUID nonce = UUID.randomUUID();
        private final Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        private long generation;

        private ParkourMenu(Player player) {
            this.player = player;
            ownerId = player.getUniqueId();
            ConfigurationSection gui = requireSection("mainGui");
            Component title = MenuAppearance.readableTitle(
                    messages.translated("mainGui.title", Map.of()),
                    gui.getString("titleColor", MenuAppearance.DEFAULT_TITLE_COLOR));
            inventory = Bukkit.createInventory(this, MainMenuLayout.SIZE, title);
            populate();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private MenuService service() {
            return MenuService.this;
        }

        private UUID ownerId() {
            return ownerId;
        }

        private UUID nonce() {
            return nonce;
        }

        private long generation() {
            if (generation <= 0L) {
                throw new IllegalStateException("GUI generation has not been bound");
            }
            return generation;
        }

        private void bindGeneration(long assignedGeneration) {
            if (generation != 0L || assignedGeneration <= 0L) {
                throw new IllegalStateException("GUI generation can only be bound once");
            }
            generation = assignedGeneration;
        }

        private Consumer<Player> actionAt(int rawSlot) {
            return actions.get(rawSlot);
        }

        private void populate() {
            ConfigurationSection gui = requireSection("mainGui");
            RuntimeSettings current = settings.get();
            if (gui.getBoolean("useFillItem", true)) {
                ItemStack fill = items.createFill(MenuAppearance.borderMaterial(
                        gui.getString("fillItem", MenuAppearance.DEFAULT_BORDER_MATERIAL)));
                for (int slot : MainMenuLayout.BORDER_SLOTS) {
                    inventory.setItem(slot, fill.clone());
                }
            }

            inventory.setItem(
                    MainMenuLayout.TUTORIAL_SLOT,
                    items.create(
                            requireSection("mainGui.tutorialItem"),
                            Map.of(
                                    "platformBlock",
                                    MenuAppearance.platformBlockLabel(
                                            current.parkourBlocks()))));
            setAction(
                    MainMenuLayout.PLAY_SLOT,
                    items.create(requireSection("mainGui.playItem")),
                    this::play);

            ConfigurationSection scoreboardSection = requireSection("mainGui.scoreboardItem");
            setAction(
                    MainMenuLayout.SCOREBOARD_SLOT,
                    items.create(scoreboardSection, scoreboardLore(scoreboardSection)),
                    this::showStats);

            ConfigurationSection playerSection =
                    requireSection("mainGui.playerItem");
            PlayerStats playerStats =
                    scores.stats(player.getUniqueId()).orElse(null);
            Map<String, Object> playerReplacements =
                    playerStatsReplacements(current, playerStats);
            setAction(
                    MainMenuLayout.PLAYER_STATS_SLOT,
                    items.createPlayerHead(
                            playerSection,
                            player,
                            playerReplacements,
                            playerStatsLore(
                                    playerSection,
                                    playerReplacements,
                                    current,
                                    playerStats)),
                    this::showStats);
            setAction(
                    MainMenuLayout.BACK_SLOT,
                    items.create(requireSection("mainGui.backItem")),
                    this::backToServerMenu);
            setAction(
                    MainMenuLayout.CLOSE_SLOT,
                    items.create(requireSection("mainGui.closeItem")),
                    Player::closeInventory);
        }

        private void play(Player clicker) {
            RuntimeSettings current = settings.get();
            String permission = current.permissions().playGame();
            if (!clicker.hasPermission(permission)) {
                messages.send(clicker, "chat.noPermissionPlay", Map.of("permissionName", permission));
                clicker.closeInventory();
                return;
            }

            GameManager.QueueStatus queue = games.queueStatus(clicker.getUniqueId());
            if (queue.playerReadyUntil().isPresent()) {
                games.startReady(clicker);
            } else if (current.queue().enabled()
                    && (queue.playerPosition() > 0
                            || queue.total() > 0
                            || games.availableArenas() == 0)) {
                games.joinQueue(clicker);
            } else {
                games.start(clicker);
            }
        }

        private void showStats(Player clicker) {
            String permission = settings.get().permissions().stats();
            if (!clicker.hasPermission(permission)) {
                messages.send(clicker, "chat.noPermissionStats", Map.of("permissionName", permission));
                clicker.closeInventory();
                return;
            }
            sendStats(clicker);
            clicker.closeInventory();
        }

        private Map<String, Object> playerStatsReplacements(
                RuntimeSettings current,
                PlayerStats stats) {
            Map<String, Object> replacements = new HashMap<>();
            replacements.put("playerName", player.getName());
            String permission = current.permissions().stats();
            replacements.put("permissionName", permission);
            if (stats != null) {
                replacements.put("playerScore", stats.bestScore());
                replacements.put("playerPlace", stats.rank());
                replacements.put("totalPlaces", stats.totalEntries());
                replacements.put("percentile", stats.percentile());
            }
            return Map.copyOf(replacements);
        }

        private List<Component> playerStatsLore(
                ConfigurationSection section,
                Map<String, Object> replacements,
                RuntimeSettings current,
                PlayerStats stats) {
            String lorePath;
            if (!player.hasPermission(current.permissions().stats())) {
                lorePath = "noPermissionLore";
            } else if (stats == null) {
                lorePath = "noScoreLore";
            } else {
                lorePath = "lore";
            }
            return section.getStringList(lorePath).stream()
                    .map(line -> messages.render(line, replacements))
                    .toList();
        }

        private void backToServerMenu(Player clicker) {
            clicker.closeInventory();
            boolean successful;
            try {
                successful = clicker.performCommand(SERVER_MENU_COMMAND);
            } catch (RuntimeException | LinkageError commandFailure) {
                successful = false;
                plugin.getLogger().log(
                        Level.WARNING,
                        "The external /menu command failed safely",
                        commandFailure);
            }
            if (!successful && clicker.isOnline()) {
                messages.send(clicker, "chat.menuUnavailable");
            }
        }

        private List<Component> scoreboardLore(ConfigurationSection section) {
            List<Component> lore = new ArrayList<>();
            String format = section.getString(
                    "scoreboardRecord",
                    "&f{{index}}. &9{{playerName}} &7({{score}})");
            for (String line : section.getStringList("lore")) {
                if (!line.toLowerCase(java.util.Locale.ROOT).contains("{{scoreboard}}")) {
                    lore.add(messages.deserialize(line));
                    continue;
                }
                String topPermission = settings.get().permissions().top();
                if (!player.hasPermission(topPermission)) {
                    List<String> hiddenLore = section.getStringList("noPermissionLore");
                    if (hiddenLore.isEmpty()) {
                        hiddenLore = List.of("&cYou do not have permission to view the leaderboard.");
                    }
                    for (String hiddenLine : hiddenLore) {
                        lore.add(messages.render(
                                hiddenLine,
                                Map.of("permissionName", topPermission)));
                    }
                    continue;
                }
                int index = 1;
                for (ScoreEntry entry : scores.top()) {
                    lore.add(messages.render(format, Map.of(
                            "playerName", entry.username(),
                            "score", entry.score(),
                            "index", index,
                            "rank", entry.rank())));
                    index++;
                }
            }
            return List.copyOf(lore);
        }

        private void setAction(int slot, ItemStack item, Consumer<Player> action) {
            inventory.setItem(slot, item);
            actions.put(slot, Objects.requireNonNull(action, "action"));
        }

        private ConfigurationSection requireSection(String path) {
            ConfigurationSection section = configuration.translations().getConfigurationSection(path);
            if (section == null) {
                throw new IllegalStateException("Missing translations section " + path);
            }
            return section;
        }
    }

    public static final class Listener implements org.bukkit.event.Listener {
        private final MenuService menus;

        public Listener(MenuService menus) {
            this.menus = Objects.requireNonNull(menus, "menus");
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent event) {
            Inventory topInventory = event.getView().getTopInventory();
            if (!(topInventory.getHolder(false) instanceof MenuService.ParkourMenu menu)
                    || menu.service() != menus) {
                return;
            }

            boolean previouslyCancelled = event.isCancelled();
            event.setCancelled(true);
            if (previouslyCancelled || !(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            GuiSessionRegistry.Session<Inventory, ParkourMenu> session =
                    menus.currentSession(player, menu, topInventory);
            if (session == null || event.getClickedInventory() != topInventory) {
                return;
            }

            int rawSlot = event.getRawSlot();
            if (rawSlot < 0 || rawSlot >= topInventory.getSize()) {
                return;
            }
            Consumer<Player> action = menu.actionAt(rawSlot);
            if (action != null) {
                menus.scheduleAction(player, session, rawSlot, action, event.getClick());
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent event) {
            Inventory topInventory = event.getView().getTopInventory();
            if (topInventory.getHolder(false) instanceof MenuService.ParkourMenu menu
                    && menu.service() == menus) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            Inventory topInventory = event.getView().getTopInventory();
            if (!(topInventory.getHolder(false) instanceof MenuService.ParkourMenu menu)
                    || menu.service() != menus) {
                return;
            }
            if (menus.sessions.removeIfCurrent(
                    event.getPlayer().getUniqueId(),
                    menu.nonce(),
                    menu.generation(),
                    topInventory)) {
                menus.actionGate.invalidate(new GuiSessionRegistry.SessionKey(
                        menu.ownerId(),
                        menu.nonce(),
                        menu.generation()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            menus.invalidate(event.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onKick(PlayerKickEvent event) {
            menus.invalidate(event.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onChangedWorld(PlayerChangedWorldEvent event) {
            menus.closeForLifecycle(event.getPlayer());
        }
    }

    public record Health(int openSessions, int pendingActions) {
        public Health {
            if (openSessions < 0 || pendingActions < 0) {
                throw new IllegalArgumentException("GUI health counts cannot be negative");
            }
        }
    }
}
