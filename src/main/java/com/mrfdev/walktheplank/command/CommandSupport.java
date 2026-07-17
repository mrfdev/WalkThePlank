package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.build.BuildInfo;
import com.mrfdev.walktheplank.config.ArenaId;
import com.mrfdev.walktheplank.config.PermissionSettings;
import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.game.Arena;
import com.mrfdev.walktheplank.game.GameManager;
import com.mrfdev.walktheplank.gui.MenuService;
import com.mrfdev.walktheplank.ops.OperationalContext;
import com.mrfdev.walktheplank.text.MessageService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared command dependencies, authorization, rendering, and main-thread handoff rules. */
final class CommandSupport {
    static final String DOCS_URL = "https://github.com/mrfdev/WalkThePlank";
    static final List<String> DEBUG_PAGES = List.of(
            "overview", "health", "hooks", "commands", "permissions", "placeholders", "config", "all");
    static final DateTimeFormatter SNAPSHOT_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss z", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    final JavaPlugin plugin;
    final Supplier<RuntimeSettings> settings;
    final GameManager games;
    final ScoreRepository scores;
    final MenuService menus;
    final MessageService messages;
    final BuildInfo buildInfo;
    final BooleanSupplier placeholderRegistered;
    final OperationalContext operations;
    final AtomicBoolean configurationMutationPending = new AtomicBoolean();

    private final AtomicBoolean closing = new AtomicBoolean();

    CommandSupport(
            JavaPlugin plugin,
            Supplier<RuntimeSettings> settings,
            GameManager games,
            ScoreRepository scores,
            MenuService menus,
            MessageService messages,
            BuildInfo buildInfo,
            BooleanSupplier placeholderRegistered,
            OperationalContext operations) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.games = Objects.requireNonNull(games, "games");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo");
        this.placeholderRegistered = Objects.requireNonNull(
                placeholderRegistered, "placeholderRegistered");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    boolean begin(CommandSender sender) {
        if (!closing.get()) {
            return true;
        }
        sendLine(sender, "&cWalkThePlank is shutting down; no new command work is accepted.");
        return false;
    }

    void closeCommands() {
        closing.set(true);
    }

    boolean closing() {
        return closing.get();
    }

    PermissionSettings permissions() {
        return settings.get().permissions();
    }

    Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "chat.playerOnly");
        return null;
    }

    boolean requirePermission(CommandSender sender, String permission) {
        if (has(sender, permission)) {
            return true;
        }
        deny(sender, permission);
        return false;
    }

    boolean requireAdministrativePermission(CommandSender sender, String permission) {
        if (hasAdministrativePermission(sender, permission)) {
            return true;
        }
        deny(sender, permission);
        return false;
    }

    boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission)
                || sender.hasPermission(permissions().admin());
    }

    boolean hasAdministrativePermission(CommandSender sender, String permission) {
        return sender.hasPermission(permissions().admin())
                || sender.hasPermission(permission);
    }

    boolean stillAuthorized(CommandSender sender, String permission) {
        if (!hasAdministrativePermission(sender, permission)) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.isOnline()
                && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    boolean hasAnyAdminPermission(CommandSender sender) {
        PermissionSettings permissions = permissions();
        return sender.hasPermission(permissions.admin())
                || sender.hasPermission(permissions.reload())
                || sender.hasPermission(permissions.adminOpen())
                || sender.hasPermission(permissions.adminDebug())
                || sender.hasPermission(permissions.adminStop())
                || sender.hasPermission(permissions.adminRecover())
                || sender.hasPermission(permissions.adminValidate())
                || sender.hasPermission(permissions.adminArena())
                || sender.hasPermission(permissions.adminQueue())
                || sender.hasPermission(permissions.adminSeason())
                || sender.hasPermission(permissions.adminExport())
                || sender.hasPermission(permissions.adminReward())
                || sender.hasPermission(permissions.adminInvestigate());
    }

    void deny(CommandSender sender, String permission) {
        messages.send(sender, "chat.noPermission", Map.of("permissionName", permission));
    }

    void addHelp(
            CommandSender sender,
            String permission,
            String syntax,
            String description) {
        if (has(sender, permission)) {
            sendCommandHelp(sender, syntax, description);
        }
    }

    void addAdminHelp(
            CommandSender sender,
            String permission,
            String syntax,
            String description) {
        if (hasAdministrativePermission(sender, permission)) {
            sendCommandHelp(sender, syntax, description);
        }
    }

    void sendHeader(CommandSender sender, String title) {
        sendLine(
                sender,
                "&7---------- &3&lWalkThePlank &8| &f{{title}} &7----------",
                Map.of("title", title));
    }

    void sendField(CommandSender sender, String name, String value) {
        sendLine(sender, "&7{{name}}: &f{{value}}", Map.of("name", name, "value", value));
    }

    void sendCommandHelp(CommandSender sender, String syntax, String description) {
        sendLine(
                sender,
                "&3{{syntax}} &7- &f{{description}}",
                Map.of("syntax", syntax, "description", description));
    }

    void sendLine(CommandSender sender, String line) {
        sender.sendMessage(messages.deserialize(line));
    }

    void sendLine(
            CommandSender sender,
            String trustedTemplate,
            Map<String, ?> literalReplacements) {
        sender.sendMessage(messages.render(trustedTemplate, literalReplacements));
    }

    <T> void completeOnMainThread(
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

    void scheduleCommandReply(CommandSender sender, Runnable reply) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, reply);
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not schedule a WalkThePlank command reply",
                    schedulingFailure);
        }
    }

    boolean awaitMainApproval(BooleanSupplier approval) throws Exception {
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

    List<String> onlinePlayerNames() {
        return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted()
                .toList();
    }

    List<String> configuredArenaIds() {
        return settings.get().arenas().stream()
                .map(Arena::id)
                .filter(ArenaId::isValid)
                .sorted()
                .toList();
    }

    static UUID operatorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    static String operatorKind(CommandSender sender) {
        return sender instanceof Player ? "player" : "system";
    }

    static String safeEvidenceText(String value, int maximumLength) {
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
}
