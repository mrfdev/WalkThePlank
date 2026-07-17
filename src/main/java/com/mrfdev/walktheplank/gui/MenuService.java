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
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class MenuService {
    private static final Duration MENU_OPEN_COOLDOWN = Duration.ofMillis(750);

    private final ConfigurationManager configuration;
    private final Supplier<RuntimeSettings> settings;
    private final ScoreRepository scores;
    private final GameManager games;
    private final MessageService messages;
    private final ItemFactory items;
    private final ActionRateLimiter rateLimiter = new ActionRateLimiter();

    public MenuService(
            ConfigurationManager configuration,
            Supplier<RuntimeSettings> settings,
            ScoreRepository scores,
            GameManager games,
            MessageService messages,
            ItemFactory items) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.games = Objects.requireNonNull(games, "games");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.items = Objects.requireNonNull(items, "items");
    }

    public void open(Player player) {
        if (!rateLimiter.tryAcquire(
                        player.getUniqueId(),
                        "menu.open",
                        MENU_OPEN_COOLDOWN,
                        Instant.now())
                .allowed()) {
            messages.send(player, "chat.slowDown");
            return;
        }
        new ParkourMenu(player).open();
    }

    public void closeOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ParkourMenu) {
                player.closeInventory();
            }
        }
    }

    public void sendStats(Player player) {
        PlayerStats stats = scores.stats(player.getUniqueId()).orElse(null);
        if (stats == null) {
            messages.send(player, "chat.chatStatsError");
            return;
        }
        int percentile = Math.max(1, (int) Math.ceil(stats.rank() * 100.0 / stats.totalEntries()));
        messages.send(player, "chat.chatStats", Map.of(
                "playerPlace", stats.rank(),
                "totalPlaces", stats.totalEntries(),
                "percentile", percentile,
                "playerScore", stats.bestScore()));
    }

    private final class ParkourMenu implements InventoryHolder {
        private static final int SIZE = 27;

        private final Player player;
        private final Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        private ParkourMenu(Player player) {
            this.player = player;
            Component title = messages.translated("mainGui.title", Map.of());
            inventory = Bukkit.createInventory(this, SIZE, title);
            populate();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void open() {
            player.openInventory(inventory);
        }

        private void click(int rawSlot, Player clicker) {
            Consumer<Player> action = actions.get(rawSlot);
            if (action != null) {
                action.accept(clicker);
            }
        }

        private void populate() {
            ConfigurationSection gui = requireSection("mainGui");
            if (gui.getBoolean("useFillItem", true)) {
                ItemStack fill = items.createFill(gui.getString("fillItem", "WHITE_STAINED_GLASS_PANE"));
                for (int slot = 0; slot < SIZE; slot++) {
                    inventory.setItem(slot, fill);
                }
            }

            inventory.setItem(10, items.create(requireSection("mainGui.tutorialItem")));
            setAction(13, items.create(requireSection("mainGui.playItem")), clicker -> {
                String permission = settings.get().permissions().playGame();
                if (!clicker.hasPermission(permission)) {
                    messages.send(clicker, "chat.noPermissionPlay", Map.of("permissionName", permission));
                    clicker.closeInventory();
                    return;
                }
                GameManager.QueueStatus queue = games.queueStatus(clicker.getUniqueId());
                if (queue.playerReadyUntil().isPresent()) {
                    games.startReady(clicker);
                } else if (settings.get().queue().enabled()
                        && (queue.playerPosition() > 0
                                || queue.total() > 0
                                || games.availableArenas() == 0)) {
                    games.joinQueue(clicker);
                } else {
                    games.start(clicker);
                }
            });

            ConfigurationSection scoreboardSection = requireSection("mainGui.scoreboardItem");
            setAction(16, items.create(scoreboardSection, scoreboardLore(scoreboardSection)), clicker -> {
                String permission = settings.get().permissions().stats();
                if (!clicker.hasPermission(permission)) {
                    messages.send(clicker, "chat.noPermissionStats", Map.of("permissionName", permission));
                    clicker.closeInventory();
                    return;
                }
                sendStats(clicker);
                clicker.closeInventory();
            });
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
                if (!player.hasPermission(settings.get().permissions().top())) {
                    List<String> hiddenLore = section.getStringList("noPermissionLore");
                    if (hiddenLore.isEmpty()) {
                        hiddenLore = List.of("&cYou do not have permission to view the leaderboard.");
                    }
                    for (String hiddenLine : hiddenLore) {
                        lore.add(messages.deserialize(hiddenLine.replace(
                                "{{permissionName}}",
                                settings.get().permissions().top())));
                    }
                    continue;
                }
                int index = 1;
                for (ScoreEntry entry : scores.top()) {
                    lore.add(messages.deserialize(format
                            .replace("{{playerName}}", entry.username())
                            .replace("{{score}}", Integer.toString(entry.score()))
                            .replace("{{index}}", Integer.toString(index))
                            .replace("{{rank}}", Integer.toString(entry.rank()))));
                    index++;
                }
            }
            return List.copyOf(lore);
        }

        private void setAction(int slot, ItemStack item, Consumer<Player> action) {
            inventory.setItem(slot, item);
            actions.put(slot, action);
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
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
        public void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof MenuService.ParkourMenu menu)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            int rawSlot = event.getRawSlot();
            if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                menu.click(rawSlot, player);
            }
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
        public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof MenuService.ParkourMenu)) {
                return;
            }
            int topSize = event.getView().getTopInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
                event.setCancelled(true);
            }
        }
    }
}
