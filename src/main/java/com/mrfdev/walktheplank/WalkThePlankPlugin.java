package com.mrfdev.walktheplank;

import com.mrfdev.walktheplank.api.DefaultWalkThePlankApi;
import com.mrfdev.walktheplank.api.WalkThePlankApi;
import com.mrfdev.walktheplank.build.BuildInfo;
import com.mrfdev.walktheplank.command.WalkCommand;
import com.mrfdev.walktheplank.config.ConfigurationManager;
import com.mrfdev.walktheplank.database.DatabaseSettings;
import com.mrfdev.walktheplank.database.JdbcScoreRepository;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.game.GameManager;
import com.mrfdev.walktheplank.gui.ItemFactory;
import com.mrfdev.walktheplank.gui.MenuService;
import com.mrfdev.walktheplank.listener.GameListener;
import com.mrfdev.walktheplank.ops.OperationalContext;
import com.mrfdev.walktheplank.ops.OperationalMetrics;
import com.mrfdev.walktheplank.ops.StructuredAuditLog;
import com.mrfdev.walktheplank.placeholder.InfinityParkourExpansion;
import com.mrfdev.walktheplank.recovery.PlayerRecoveryJournal;
import com.mrfdev.walktheplank.recovery.RestorationJournal;
import com.mrfdev.walktheplank.reward.RewardService;
import com.mrfdev.walktheplank.text.MessageService;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

public final class WalkThePlankPlugin extends JavaPlugin {
    private ConfigurationManager configuration;
    private ScoreRepository scores;
    private GameManager games;
    private MenuService menus;
    private MessageService messages;
    private DatabaseSettings activeDatabaseSettings;
    private InfinityParkourExpansion placeholderExpansion;
    private BuildInfo buildInfo;
    private OperationalContext operations;
    private WalkThePlankApi api;
    private boolean enableCompleted;

    @Override
    public void onEnable() {
        try {
            Path dataFolder = requireSafeDataFolder();
            buildInfo = BuildInfo.load(this);
            String releaseIdentity = buildInfo.releaseLabel() + " / " + buildInfo.artifactFile();
            StructuredAuditLog auditLog = StructuredAuditLog.open(
                    dataFolder, releaseIdentity);
            operations = new OperationalContext(new OperationalMetrics(), auditLog, getLogger());
            RestorationJournal restorationJournal = RestorationJournal.open(
                    dataFolder, releaseIdentity);
            PlayerRecoveryJournal playerRecoveryJournal = PlayerRecoveryJournal.open(dataFolder);
            configuration = new ConfigurationManager(this);
            configuration.load();
            activeDatabaseSettings = configuration.databaseSettings();

            scores = new JdbcScoreRepository(activeDatabaseSettings, getLogger());
            scores.initialize();

            messages = new MessageService(configuration::translations);
            RewardService rewards = new RewardService(configuration::runtimeSettings, getLogger());
            games = new GameManager(
                    this,
                    configuration::runtimeSettings,
                    scores,
                    messages,
                    rewards,
                    restorationJournal,
                    playerRecoveryJournal,
                    releaseIdentity,
                    operations);
            getServer().getScheduler().runTaskTimer(this, games::expireSessions, 20L, 20L);

            api = new DefaultWalkThePlankApi(buildInfo, scores, games);
            getServer().getServicesManager().register(
                    WalkThePlankApi.class, api, this, ServicePriority.Normal);

            ItemFactory itemFactory = new ItemFactory(messages, getLogger());
            menus = new MenuService(
                    configuration,
                    configuration::runtimeSettings,
                    scores,
                    games,
                    messages,
                    itemFactory);

            getServer().getPluginManager().registerEvents(
                    new GameListener(this, games, scores, messages), this);
            getServer().getPluginManager().registerEvents(new MenuService.Listener(), this);

            PluginCommand command = Objects.requireNonNull(
                    getCommand("walktheplank"),
                    "walktheplank command is missing from plugin.yml");
            WalkCommand commandHandler = new WalkCommand(
                    this,
                    configuration::runtimeSettings,
                    games,
                    scores,
                    menus,
                    messages,
                    this::reloadPlugin,
                    buildInfo,
                    () -> placeholderExpansion != null,
                    operations);
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);

            registerPlaceholderExpansion();
            for (Player player : getServer().getOnlinePlayers()) {
                games.retryPendingReturn(player);
                String playerName = player.getName();
                scores.touchIdentity(player.getUniqueId(), playerName).exceptionally(failure -> {
                    getLogger().log(
                            Level.WARNING,
                            "Could not refresh score identity for " + playerName,
                            failure);
                    return null;
                });
            }

            Optional<Path> backup = scores.migrationBackup();
            backup.ifPresent(path -> getLogger().info(
                    "Verified pre-migration database backup: " + path));
            operations.audit("plugin.enable", null, null, java.util.Map.of(
                    "scores", scores.snapshot().totalEntries(),
                    "pending_restorations", games.pendingRestorations(),
                    "pending_player_recoveries", games.pendingPlayerRecoveries()));
            enableCompleted = true;
            getLogger().info("1MB-WalkThePlank " + buildInfo.releaseLabel()
                    + " (" + buildInfo.sourceLabel() + ") enabled with "
                    + scores.snapshot().totalEntries() + " preserved scores");
        } catch (Exception | LinkageError exception) {
            enableCompleted = false;
            if (operations != null) {
                try {
                    operations.audit("plugin.enable_failed", null, null, java.util.Map.of(
                            "failure", exception.getClass().getSimpleName()));
                } catch (RuntimeException | LinkageError auditFailure) {
                    exception.addSuppressed(auditFailure);
                }
            }
            getLogger().log(Level.SEVERE, "WalkThePlank could not start safely; disabling plugin", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private Path requireSafeDataFolder() throws IOException {
        Path dataFolder = getDataFolder().toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(dataFolder)
                || Files.exists(dataFolder, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(dataFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin data folder must be a safe regular directory");
        }
        Files.createDirectories(dataFolder);
        if (Files.isSymbolicLink(dataFolder)
                || !Files.isDirectory(dataFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin data folder must be a safe regular directory");
        }
        return dataFolder;
    }

    @Override
    public void onDisable() {
        boolean cleanDisable = enableCompleted;
        long repositoryFailuresBeforeDrain = 0L;
        if (operations != null) {
            try {
                repositoryFailuresBeforeDrain = operations.metrics().snapshot().repositoryFailures();
            } catch (RuntimeException | LinkageError exception) {
                cleanDisable = false;
                getLogger().log(Level.SEVERE, "Could not snapshot pre-shutdown metrics", exception);
            }
        }
        try {
            getServer().getServicesManager().unregisterAll(this);
        } catch (RuntimeException | LinkageError exception) {
            cleanDisable = false;
            getLogger().log(Level.SEVERE, "Could not unregister WalkThePlank services", exception);
        }
        api = null;
        if (menus != null) {
            try {
                menus.closeOpenMenus();
            } catch (RuntimeException | LinkageError exception) {
                cleanDisable = false;
                getLogger().log(Level.SEVERE, "Could not close WalkThePlank menus", exception);
            }
        }
        if (games != null) {
            try {
                if (!games.shutdown()) {
                    cleanDisable = false;
                    getLogger().severe("One or more arenas remain quarantined after shutdown retry");
                }
            } catch (RuntimeException | LinkageError exception) {
                cleanDisable = false;
                getLogger().log(Level.SEVERE, "Could not finish WalkThePlank sessions", exception);
            }
        }
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.unregister();
            } catch (RuntimeException | LinkageError exception) {
                cleanDisable = false;
                getLogger().log(Level.SEVERE, "Could not unregister PlaceholderAPI integration", exception);
            }
            placeholderExpansion = null;
        }
        if (scores != null) {
            try {
                if (!scores.close(Duration.ofSeconds(15))) {
                    cleanDisable = false;
                    getLogger().warning("Timed out while draining pending score writes during shutdown");
                }
            } catch (RuntimeException | LinkageError exception) {
                cleanDisable = false;
                getLogger().log(Level.SEVERE, "Could not close the score repository", exception);
            }
        }
        if (operations != null) {
            try {
                if (operations.metrics().snapshot().repositoryFailures() > repositoryFailuresBeforeDrain) {
                    cleanDisable = false;
                    getLogger().warning(
                            "One or more accepted database operations failed while shutdown was draining");
                }
            } catch (RuntimeException | LinkageError exception) {
                cleanDisable = false;
                getLogger().log(Level.SEVERE, "Could not snapshot post-shutdown metrics", exception);
            }
        }
        if (operations != null) {
            try {
                operations.audit("plugin.disable", null, null, java.util.Map.of(
                        "clean", cleanDisable,
                        "startup_completed", enableCompleted,
                        "pending_restorations", games == null ? 0 : games.pendingRestorations(),
                        "pending_player_recoveries", games == null ? 0 : games.pendingPlayerRecoveries()));
            } catch (RuntimeException | LinkageError exception) {
                cleanDisable = false;
                getLogger().log(Level.SEVERE, "Could not record final WalkThePlank shutdown audit", exception);
            }
        }
        if (!enableCompleted) {
            getLogger().warning(
                    "WalkThePlank startup was aborted; no clean restoration claim is being made");
        } else if (cleanDisable) {
            getLogger().info("WalkThePlank disabled; arena blocks and player state were restored");
        } else {
            getLogger().warning("WalkThePlank disabled with cleanup errors; review the messages above");
        }
    }

    private boolean reloadPlugin() {
        ConfigurationManager.ConfigurationSnapshot previous = null;
        boolean candidatePublished = false;
        try {
            ConfigurationManager.ConfigurationSnapshot candidate = configuration.prepare();
            previous = configuration.activeSnapshot();
            if (menus != null) {
                menus.closeOpenMenus();
            }
            games.prepareReload();
            configuration.commit(candidate);
            candidatePublished = true;
            try {
                games.applyReloadedSettings();
            } catch (RuntimeException | LinkageError applyFailure) {
                configuration.commit(previous);
                candidatePublished = false;
                try {
                    games.applyReloadedSettings();
                } catch (RuntimeException | LinkageError rollbackFailure) {
                    applyFailure.addSuppressed(rollbackFailure);
                }
                throw applyFailure;
            }
            if (!candidate.databaseSettings().equals(activeDatabaseSettings)) {
                getLogger().warning("Database settings changed; restart the server to apply them safely");
            }
            return true;
        } catch (Exception | LinkageError failure) {
            if (candidatePublished && previous != null) {
                configuration.commit(previous);
                try {
                    games.applyReloadedSettings();
                } catch (RuntimeException | LinkageError rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            getLogger().log(
                    Level.SEVERE,
                    "Could not reload WalkThePlank; the previous validated configuration remains active",
                    failure);
            return false;
        }
    }

    private void registerPlaceholderExpansion() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            placeholderExpansion = new InfinityParkourExpansion(this, scores, games);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered built-in PlaceholderAPI expansion");
            } else {
                getLogger().warning("PlaceholderAPI rejected the built-in expansion registration");
                placeholderExpansion = null;
            }
        } catch (RuntimeException | LinkageError failure) {
            placeholderExpansion = null;
            getLogger().log(
                    Level.WARNING,
                    "PlaceholderAPI integration is unavailable; core WalkThePlank features remain enabled",
                    failure);
        }
    }
}
