package com.mrfdev.walktheplank.config;

import com.mrfdev.walktheplank.database.DatabaseSettings;
import com.mrfdev.walktheplank.database.RewardPlanRequest;
import com.mrfdev.walktheplank.game.Arena;
import com.mrfdev.walktheplank.game.ArenaBounds;
import com.mrfdev.walktheplank.game.ArenaSelectionPolicy;
import com.mrfdev.walktheplank.game.SafePlayerExit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockType;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigurationManager {
    private static final int MAXIMUM_ARENAS = 64;
    private static final Pattern TRANSLATION_PLACEHOLDER =
            Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}");
    private static final Set<String> ARENA_ENTRY_KEYS = Set.of(
            "id",
            "x",
            "y",
            "z",
            "pitch",
            "yaw",
            "world",
            "useCustomEndPosition",
            "endPos");
    private static final Set<String> LOCATION_ENTRY_KEYS =
            Set.of("x", "y", "z", "pitch", "yaw", "world");
    private static final Set<String> REWARD_TIER_KEYS =
            Set.of("commands", "minScore", "maxScore");
    private static final List<String> TRANSLATION_STRING_PATHS = List.of(
            "scoreboardRecordInChat.record",
            "chat.prefix",
            "chat.wrongUsage",
            "chat.slowDown",
            "chat.playerOnly",
            "chat.playerNotFound",
            "chat.noPermissionGui",
            "chat.noPermissionLeave",
            "chat.noPermissionPlay",
            "chat.noPermissionReload",
            "chat.noPermissionTop",
            "chat.noPermissionStats",
            "chat.noPermission",
            "chat.reloadSuccess",
            "chat.reloadFailed",
            "chat.cantTeleport",
            "chat.areaLeave",
            "chat.meaninglessLeave",
            "chat.arenaStart",
            "chat.allArenasUsed",
            "chat.queueOffer",
            "chat.queueFairness",
            "chat.queueDisabled",
            "chat.queuePaused",
            "chat.queueJoined",
            "chat.queueAlready",
            "chat.queueReady",
            "chat.queueReadyActionbar",
            "chat.queuePositionActionbar",
            "chat.queueNotReady",
            "chat.queueExpired",
            "chat.queueCooldown",
            "chat.queueLeft",
            "chat.queueNotQueued",
            "chat.queueRemoved",
            "chat.queueDrained",
            "chat.preparingRun",
            "chat.startCancelled",
            "chat.chatStatsError",
            "chat.chatStats",
            "chat.alreadyInGame",
            "chat.newRecord",
            "chat.blockInArena",
            "chat.unsupportedGameMode",
            "chat.movementStateActive",
            "chat.movementEffectActive",
            "chat.movementAttributeActive",
            "chat.startFailed",
            "chat.sessionFailed",
            "chat.runIntegrityFailed",
            "chat.runTimedOut",
            "chat.menuUnavailable",
            "mainGui.title",
            "mainGui.titleColor",
            "mainGui.fillItem",
            "mainGui.tutorialItem.item",
            "mainGui.tutorialItem.title",
            "mainGui.playItem.item",
            "mainGui.playItem.title",
            "mainGui.scoreboardItem.item",
            "mainGui.scoreboardItem.title",
            "mainGui.scoreboardItem.scoreboardRecord",
            "mainGui.playerItem.item",
            "mainGui.playerItem.title",
            "mainGui.backItem.item",
            "mainGui.backItem.title",
            "mainGui.closeItem.item",
            "mainGui.closeItem.title");
    private static final List<String> TRANSLATION_STRING_LIST_PATHS = List.of(
            "scoreboardRecordInChat.prefix",
            "scoreboardRecordInChat.suffix",
            "chat.scoreMsgs",
            "helpCommand",
            "mainGui.tutorialItem.lore",
            "mainGui.playItem.lore",
            "mainGui.scoreboardItem.noPermissionLore",
            "mainGui.scoreboardItem.lore",
            "mainGui.playerItem.noPermissionLore",
            "mainGui.playerItem.noScoreLore",
            "mainGui.playerItem.lore",
            "mainGui.backItem.lore",
            "mainGui.closeItem.lore");
    private static final List<String> TRANSLATION_BOOLEAN_PATHS = List.of(
            "mainGui.useFillItem",
            "mainGui.tutorialItem.glow",
            "mainGui.playItem.glow",
            "mainGui.scoreboardItem.glow",
            "mainGui.playerItem.glow",
            "mainGui.backItem.glow",
            "mainGui.closeItem.glow");
    private static final Set<String> KNOWN_CONFIG_KEYS = Set.of(
            "configVersion",
            "startPositions",
            "parkourBlocks",
            "gameplay",
            "gameplay.fallDistance",
            "gameplay.horizontalRadius",
            "gameplay.maximumRunSeconds",
            "gameplay.idleTimeoutSeconds",
            "gameplay.onlyReplaceAir",
            "particle",
            "particle.show",
            "particle.type",
            "particle.count",
            "runFinishCommands",
            "rewards",
            "rewards.onlyOnPersonalBest",
            "rewards.allowedCommandRoots",
            "queue",
            "queue.enabled",
            "queue.joinCooldownSeconds",
            "queue.readinessWindowSeconds",
            "queue.reminderIntervalSeconds",
            "arenaSelection",
            "arenaSelection.policy",
            "arenaSelection.pinnedArena",
            "milestones",
            "milestones.enabled",
            "milestones.scores",
            "milestones.sound",
            "milestones.particle",
            "milestones.particleCount",
            "milestones.cooldownSeconds",
            "categories",
            "categories.combo",
            "categories.combo.enabled",
            "categories.combo.maximumGapSeconds",
            "antiCheat",
            "antiCheat.enabled",
            "antiCheat.blockProjectiles",
            "antiCheat.blockRiptide",
            "antiCheat.blockExploitTeleports",
            "antiCheat.minimumJumpIntervalMillis",
            "antiCheat.anomalyAuditCooldownSeconds",
            "finishCommands",
            "permissions",
            "permissions.openGui",
            "permissions.leaveArena",
            "permissions.playGame",
            "permissions.reload",
            "permissions.statsCmd",
            "permissions.topCmd",
            "permissions.info",
            "permissions.help",
            "permissions.preferences",
            "permissions.admin",
            "permissions.adminOpen",
            "permissions.adminDebug",
            "permissions.adminStop",
            "permissions.adminRecover",
            "permissions.adminValidate",
            "permissions.adminArena",
            "permissions.adminQueue",
            "permissions.adminSeason",
            "permissions.adminExport",
            "permissions.adminReward",
            "permissions.adminInvestigate",
            "database",
            "database.type",
            "database.sqlite",
            "database.sqlite.file",
            "database.sqlite.busyTimeoutMillis",
            "database.sqlite.migrationBackupRetention");

    private final JavaPlugin plugin;
    private final Path dataFolder;
    private final String bundledConfigDefaults;
    private final String bundledTranslationDefaults;

    private volatile ConfigurationSnapshot active;
    private long generation;

    public ConfigurationManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        this.bundledConfigDefaults = readBundledResource("config.yml");
        this.bundledTranslationDefaults = readBundledResource("translations.yml");
    }

    public void load() throws IOException {
        commit(prepare());
    }

    /** Startup-only disk preparation. Runtime callers must capture files on the operations worker. */
    public ConfigurationSnapshot prepare() throws IOException {
        YamlConfiguration newConfig = loadWithDefaults("config.yml");
        YamlConfiguration newTranslations = loadWithDefaults("translations.yml");
        return prepareParsed(newConfig, newTranslations, true);
    }

    /** Reads immutable configuration bytes. This method performs filesystem I/O. */
    public ConfigurationFiles captureFiles() throws IOException {
        Path dataFolder = requireSafeDataFolder();
        Path configFile = requireSafeRegularFile(dataFolder, "config.yml");
        Path translationsFile = requireSafeRegularFile(dataFolder, "translations.yml");
        return new ConfigurationFiles(
                Files.readAllBytes(configFile),
                Files.readAllBytes(translationsFile));
    }

    /** Fails when either file changed after a worker capture. This method performs filesystem I/O. */
    public void requireFilesUnchanged(ConfigurationFiles expected) throws IOException {
        ConfigurationFiles checked = Objects.requireNonNull(expected, "expected");
        ConfigurationFiles current = captureFiles();
        if (!Arrays.equals(checked.configBytes(), current.configBytes())
                || !Arrays.equals(checked.translationBytes(), current.translationBytes())) {
            throw new IOException(
                    "Configuration files changed while the operation was being validated; retry the command");
        }
    }

    /** Worker-only storage target verification for a main-thread-validated candidate. */
    public void requireDatabaseStorageSafe(DatabaseSettings settings) throws IOException {
        DatabaseSettings checked = Objects.requireNonNull(settings, "settings");
        try {
            Path databasePath = checked.databasePath();
            if (!databasePath.startsWith(dataFolder)) {
                throw new IllegalArgumentException(
                        "SQLite database path must stay inside the plugin data folder");
            }
            SqlitePathGuard.resolve(
                    dataFolder,
                    dataFolder.relativize(databasePath).toString());
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Configured SQLite storage is not a safe usable target", exception);
        }
    }

    /** Worker-only post-commit check for the companion translations file. */
    public void requireTranslationsUnchanged(ConfigurationFiles expected) throws IOException {
        ConfigurationFiles checked = Objects.requireNonNull(expected, "expected");
        Path dataFolder = requireSafeDataFolder();
        Path translationsFile = requireSafeRegularFile(dataFolder, "translations.yml");
        if (!Arrays.equals(
                checked.translationBytes(),
                Files.readAllBytes(translationsFile))) {
            throw new IOException(
                    "translations.yml changed while config.yml was being committed");
        }
    }

    /** Parses captured bytes and performs live Paper/Bukkit validation without disk access. */
    public ConfigurationSnapshot prepare(ConfigurationFiles files) throws IOException {
        ConfigurationFiles checked = Objects.requireNonNull(files, "files");
        return prepareParsed(
                parseCaptured(checked.configBytes(), "config.yml"),
                parseCaptured(checked.translationBytes(), "translations.yml"),
                false);
    }

    private ConfigurationSnapshot prepareParsed(
            YamlConfiguration newConfig,
            YamlConfiguration newTranslations,
            boolean verifyDatabaseStorage) throws IOException {
        ValidationAccumulator problems = new ValidationAccumulator();
        validateEffectiveConfiguration(newConfig, problems, verifyDatabaseStorage);
        validateTranslationEntries(newTranslations, problems);
        ConfigurationValidationReport report = problems.report(
                SafeConfigFingerprint.fingerprint(newConfig));
        if (!report.valid()) {
            throw new IOException("Configuration validation failed (hash "
                    + report.fingerprint() + "): " + String.join("; ", report.errors()));
        }
        RuntimeSettings newSettings = parseRuntimeSettings(newConfig);
        DatabaseSettings newDatabaseSettings =
                parseDatabaseSettings(newConfig, verifyDatabaseStorage);

        return new ConfigurationSnapshot(
                newConfig,
                newTranslations,
                newSettings,
                newDatabaseSettings);
    }

    /** Atomically publishes one fully validated configuration bundle. */
    public synchronized void commit(ConfigurationSnapshot candidate) {
        active = Objects.requireNonNull(candidate, "candidate");
        generation = Math.incrementExact(generation);
    }

    /** Publishes only if no newer runtime configuration won the race. */
    public synchronized boolean commitIfGeneration(
            ConfigurationSnapshot candidate,
            long expectedGeneration) {
        if (generation != expectedGeneration) {
            return false;
        }
        active = Objects.requireNonNull(candidate, "candidate");
        generation = Math.incrementExact(generation);
        return true;
    }

    public synchronized long generation() {
        return generation;
    }

    public ConfigurationSnapshot activeSnapshot() {
        return Objects.requireNonNull(active, "Configuration has not been loaded");
    }

    public YamlConfiguration config() {
        return activeSnapshot().config();
    }

    public YamlConfiguration translations() {
        return activeSnapshot().translations();
    }

    public RuntimeSettings runtimeSettings() {
        return activeSnapshot().runtimeSettings();
    }

    public DatabaseSettings databaseSettings() {
        return activeSnapshot().databaseSettings();
    }

    public record ConfigurationSnapshot(
            YamlConfiguration config,
            YamlConfiguration translations,
            RuntimeSettings runtimeSettings,
            DatabaseSettings databaseSettings) {
        public ConfigurationSnapshot {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(translations, "translations");
            Objects.requireNonNull(runtimeSettings, "runtimeSettings");
            Objects.requireNonNull(databaseSettings, "databaseSettings");
        }
    }

    /** Immutable byte-for-byte capture made by the operations I/O worker. */
    public record ConfigurationFiles(byte[] configBytes, byte[] translationBytes) {
        public ConfigurationFiles {
            configBytes = Objects.requireNonNull(configBytes, "configBytes").clone();
            translationBytes = Objects.requireNonNull(translationBytes, "translationBytes").clone();
        }

        @Override
        public byte[] configBytes() {
            return configBytes.clone();
        }

        @Override
        public byte[] translationBytes() {
            return translationBytes.clone();
        }
    }

    /** Validates worker-captured files without changing active settings or reading a file. */
    public ConfigurationValidationReport validate(ConfigurationFiles files) {
        ConfigurationFiles checked = Objects.requireNonNull(files, "files");
        ValidationAccumulator problems = new ValidationAccumulator();
        YamlConfiguration candidate = parseCaptured(
                checked.configBytes(), "config.yml", problems);
        YamlConfiguration candidateTranslations = parseCaptured(
                checked.translationBytes(), "translations.yml", problems);
        if (candidate != null) {
            validateEffectiveConfiguration(candidate, problems, false);
        }
        if (candidateTranslations != null) {
            validateTranslationEntries(candidateTranslations, problems);
        }
        String fingerprint = candidate == null
                ? "unavailable"
                : SafeConfigFingerprint.fingerprint(candidate);
        return problems.report(fingerprint);
    }

    /** Validates an edited candidate against the same effective defaults and translation file. */
    public ConfigurationValidationReport validateCandidate(
            YamlConfiguration candidate,
            ConfigurationFiles files) {
        Objects.requireNonNull(candidate, "candidate");
        ConfigurationFiles checked = Objects.requireNonNull(files, "files");
        ValidationAccumulator problems = new ValidationAccumulator();
        YamlConfiguration effective = copyWithDefaults(candidate, "config.yml", problems);
        YamlConfiguration candidateTranslations = parseCaptured(
                checked.translationBytes(), "translations.yml", problems);
        if (effective != null) {
            validateEffectiveConfiguration(effective, problems, false);
        }
        if (candidateTranslations != null) {
            validateTranslationEntries(candidateTranslations, problems);
        }
        String fingerprint = effective == null
                ? "unavailable"
                : SafeConfigFingerprint.fingerprint(effective);
        return problems.report(fingerprint);
    }

    YamlConfiguration editableConfig(ConfigurationFiles files) throws IOException {
        return parseCaptured(
                Objects.requireNonNull(files, "files").configBytes(),
                "config.yml");
    }

    ConfigurationSnapshot prepareCandidate(
            YamlConfiguration candidate,
            ConfigurationFiles files) throws IOException {
        Objects.requireNonNull(candidate, "candidate");
        ConfigurationFiles checked = Objects.requireNonNull(files, "files");
        YamlConfiguration translations = parseCaptured(
                checked.translationBytes(), "translations.yml");
        return prepareParsed(candidate, translations, false);
    }

    private YamlConfiguration parseCaptured(byte[] bytes, String resourceName)
            throws IOException {
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.options().parseComments(true);
        try {
            loaded.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            throw new IOException(resourceName + " contains invalid YAML");
        }
        ValidationAccumulator problems = new ValidationAccumulator();
        YamlConfiguration effective = applyDefaults(loaded, resourceName, problems);
        if (effective == null) {
            throw new IOException(resourceName + " bundled defaults could not be applied");
        }
        return effective;
    }

    private YamlConfiguration parseCaptured(
            byte[] bytes,
            String resourceName,
            ValidationAccumulator problems) {
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.options().parseComments(true);
        try {
            loaded.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            problems.error(resourceName + " could not be parsed");
            return null;
        }
        return applyDefaults(loaded, resourceName, problems);
    }

    private YamlConfiguration copyWithDefaults(
            YamlConfiguration source,
            String resourceName,
            ValidationAccumulator problems) {
        YamlConfiguration copy = new YamlConfiguration();
        copy.options().parseComments(true);
        try {
            copy.loadFromString(source.saveToString());
        } catch (InvalidConfigurationException exception) {
            problems.error(resourceName + " candidate could not be parsed");
            return null;
        }
        return applyDefaults(copy, resourceName, problems);
    }

    private YamlConfiguration applyDefaults(
            YamlConfiguration loaded,
            String resourceName,
            ValidationAccumulator problems) {
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            defaults.loadFromString(bundledDefaults(resourceName));
        } catch (InvalidConfigurationException | IllegalArgumentException exception) {
            problems.error("Bundled " + resourceName + " could not be parsed");
            return null;
        }
        loaded.setDefaults(defaults);
        if ("translations.yml".equals(resourceName)) {
            GuiTranslationCompatibility.upgrade(loaded, defaults);
        }
        return loaded;
    }

    private void validateEffectiveConfiguration(
            YamlConfiguration source,
            ValidationAccumulator problems,
            boolean verifyDatabaseStorage) {
        validateKnownKeys(source, problems);
        validatePrimitiveSettings(source, problems);
        validateArenaEntries(source, problems);
        validateParkourBlockEntries(source, problems);
        validateRewardEntries(source, problems);
        validatePermissionEntries(source, problems);
        validateDatabaseEntries(source, problems, verifyDatabaseStorage);

        // These are the authoritative startup/reload parsers. Keeping them as a final gate prevents
        // the diagnostic schema from drifting away from the configuration that actually becomes active.
        try {
            parseRuntimeSettings(source);
        } catch (RuntimeException exception) {
            problems.error("The runtime parser rejected one or more effective settings");
        }
        try {
            parseDatabaseSettings(source, verifyDatabaseStorage);
        } catch (RuntimeException exception) {
            problems.error("Database settings are not accepted by the runtime parser");
        }
    }

    private YamlConfiguration loadWithDefaults(String resourceName) throws IOException {
        if (Files.isSymbolicLink(dataFolder)
                || Files.exists(dataFolder, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(dataFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin data folder is not a safe regular directory");
        }
        Files.createDirectories(dataFolder);
        if (Files.isSymbolicLink(dataFolder)
                || !Files.isDirectory(dataFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin data folder is not a safe regular directory");
        }
        Path file = dataFolder.resolve(resourceName);
        if (Files.isSymbolicLink(file)) {
            throw new IOException(resourceName + " must not be a symbolic link");
        }
        if (!Files.isRegularFile(file)) {
            plugin.saveResource(resourceName, false);
        }
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IOException(resourceName + " is not a safe regular file");
        }

        YamlConfiguration loaded = new YamlConfiguration();
        loaded.options().parseComments(true);
        try {
            loaded.load(file.toFile());
        } catch (InvalidConfigurationException exception) {
            // Parser messages can echo a source line containing a trusted reward command or a
            // legacy credential. Keep detailed content out of startup/reload logs.
            throw new IOException(resourceName + " contains invalid YAML");
        }
        ValidationAccumulator problems = new ValidationAccumulator();
        if (applyDefaults(loaded, resourceName, problems) == null) {
            throw new IOException("Bundled defaults for " + resourceName + " are invalid");
        }
        return loaded;
    }

    private Path requireSafeDataFolder() throws IOException {
        if (Files.isSymbolicLink(dataFolder)
                || !Files.isDirectory(dataFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin data folder is not a safe regular directory");
        }
        return dataFolder;
    }

    private static Path requireSafeRegularFile(Path dataFolder, String resourceName)
            throws IOException {
        Path file = dataFolder.resolve(resourceName);
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(resourceName + " is not a safe regular file");
        }
        return file;
    }

    private String bundledDefaults(String resourceName) {
        return switch (resourceName) {
            case "config.yml" -> bundledConfigDefaults;
            case "translations.yml" -> bundledTranslationDefaults;
            default -> throw new IllegalArgumentException(
                    "Unsupported bundled configuration resource " + resourceName);
        };
    }

    private String readBundledResource(String resourceName) {
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled resource " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not cache bundled resource " + resourceName, exception);
        }
    }

    private RuntimeSettings parseRuntimeSettings(YamlConfiguration source) {
        List<Arena> arenas = parseArenas(source);
        List<Material> blocks = parseParkourBlocks(source);
        Particle particle = parseParticle(source.getString("particle.type", "TOTEM_OF_UNDYING"));
        List<RewardTier> rewardTiers = parseRewardTiers(source);

        PermissionSettings permissions = parsePermissionSettings(source);

        RuntimeSettings parsedSettings = new RuntimeSettings(
                source.getInt("configVersion", 2),
                arenas,
                blocks,
                source.getBoolean("particle.show", true),
                particle,
                source.getInt("particle.count", 12),
                source.getDouble("gameplay.fallDistance", 6.0),
                source.getInt("gameplay.horizontalRadius", 6),
                source.getInt("gameplay.maximumRunSeconds", 1_800),
                source.getInt("gameplay.idleTimeoutSeconds", 300),
                source.getBoolean("gameplay.onlyReplaceAir", true),
                source.getBoolean("runFinishCommands", false),
                source.getBoolean("rewards.onlyOnPersonalBest", false),
                RewardCommandRootPolicy.parse(source.get("rewards.allowedCommandRoots")),
                rewardTiers,
                new QueueSettings(
                        source.getBoolean("queue.enabled", true),
                        Duration.ofSeconds(source.getLong("queue.joinCooldownSeconds", 5L)),
                        Duration.ofSeconds(source.getLong("queue.readinessWindowSeconds", 20L)),
                        Duration.ofSeconds(source.getLong("queue.reminderIntervalSeconds", 5L))),
                new ArenaSelectionSettings(
                        ArenaSelectionPolicy.parse(source.getString("arenaSelection.policy", "RANDOM")),
                        Optional.ofNullable(source.getString("arenaSelection.pinnedArena"))),
                new MilestoneSettings(
                        source.getBoolean("milestones.enabled", true),
                        source.getIntegerList("milestones.scores"),
                        parseSound(source.getString(
                                "milestones.sound", "minecraft:entity.player.levelup")),
                        parseParticle(source.getString(
                                "milestones.particle", "HAPPY_VILLAGER")),
                        source.getInt("milestones.particleCount", 20),
                        Duration.ofSeconds(source.getLong("milestones.cooldownSeconds", 2L))),
                new ComboSettings(
                        source.getBoolean("categories.combo.enabled", true),
                        Duration.ofSeconds(source.getLong(
                                "categories.combo.maximumGapSeconds", 8L))),
                new AntiCheatSettings(
                        source.getBoolean("antiCheat.enabled", true),
                        source.getBoolean("antiCheat.blockProjectiles", true),
                        source.getBoolean("antiCheat.blockRiptide", true),
                        source.getBoolean("antiCheat.blockExploitTeleports", true),
                        Duration.ofMillis(source.getLong(
                                "antiCheat.minimumJumpIntervalMillis", 150L)),
                        Duration.ofSeconds(source.getLong(
                                "antiCheat.anomalyAuditCooldownSeconds", 10L))),
                permissions);
        validateArenaLayout(parsedSettings);
        return parsedSettings;
    }

    private static PermissionSettings parsePermissionSettings(YamlConfiguration source) {
        return new PermissionSettings(
                source.getString("permissions.openGui", "infinityparkour.opengui"),
                source.getString("permissions.leaveArena", "infinityparkour.leavearena"),
                source.getString("permissions.playGame", "infinityparkour.play"),
                source.getString("permissions.reload", "infinityparkour.reload"),
                source.getString("permissions.statsCmd", "infinityparkour.statscmd"),
                source.getString("permissions.topCmd", "infinityparkour.topcmd"),
                source.getString("permissions.info", "infinityparkour.info"),
                source.getString("permissions.help", "infinityparkour.help"),
                source.getString(
                        "permissions.preferences",
                        "infinityparkour.preferences"),
                source.getString("permissions.admin", "infinityparkour.admin"),
                source.getString("permissions.adminOpen", "infinityparkour.admin.open"),
                source.getString("permissions.adminDebug", "infinityparkour.admin.debug"),
                source.getString("permissions.adminStop", "infinityparkour.admin.stop"),
                source.getString("permissions.adminRecover", "infinityparkour.admin.recover"),
                source.getString("permissions.adminValidate", "infinityparkour.admin.validate"),
                source.getString("permissions.adminArena", "infinityparkour.admin.arena"),
                source.getString("permissions.adminQueue", "infinityparkour.admin.queue"),
                source.getString("permissions.adminSeason", "infinityparkour.admin.season"),
                source.getString("permissions.adminExport", "infinityparkour.admin.export"),
                source.getString("permissions.adminReward", "infinityparkour.admin.reward"),
                source.getString(
                        "permissions.adminInvestigate",
                        "infinityparkour.admin.investigate"));
    }

    private DatabaseSettings parseDatabaseSettings(
            YamlConfiguration source,
            boolean verifyStorage) {
        return parseDatabaseSettings(
                source, dataFolder, verifyStorage);
    }

    static DatabaseSettings parseDatabaseSettings(YamlConfiguration source, Path pluginDataFolder) {
        return parseDatabaseSettings(source, pluginDataFolder, true);
    }

    private static DatabaseSettings parseDatabaseSettings(
            YamlConfiguration source,
            Path pluginDataFolder,
            boolean verifyStorage) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(pluginDataFolder, "pluginDataFolder");

        boolean legacyMySqlEnabled = source.getBoolean("mysql.enabled", false)
                || source.getBoolean("database.mysql.enabled", false);
        String configuredType = source.getString("database.type");
        DatabaseBackendPolicy.requireSQLite(configuredType, legacyMySqlEnabled);
        if (source.contains("database.type", true) && configuredType == null) {
            throw new IllegalArgumentException("database.type must be the string SQLITE");
        }

        String configuredFile = source.getString("database.sqlite.file", "database.db");
        Path databaseFile = verifyStorage
                ? SqlitePathGuard.resolve(pluginDataFolder, configuredFile)
                : resolveConfinedDatabasePath(pluginDataFolder, configuredFile);
        long timeoutMillis = source.getLong("database.sqlite.busyTimeoutMillis", 5_000L);
        int backupRetention = source.getInt(
                "database.sqlite.migrationBackupRetention",
                DatabaseSettings.DEFAULT_MIGRATION_BACKUP_RETENTION);
        return DatabaseSettings.sqlite(
                databaseFile,
                Duration.ofMillis(timeoutMillis),
                backupRetention);
    }

    private static Path resolveConfinedDatabasePath(
            Path pluginDataFolder,
            String configuredFile) {
        if (configuredFile == null || configuredFile.isBlank()) {
            throw new IllegalArgumentException(
                    "database.sqlite.file must be a nonblank relative file name");
        }
        Path relative;
        try {
            relative = Path.of(configuredFile);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(
                    "database.sqlite.file is not a valid confined path", exception);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException(
                    "database.sqlite.file must be relative to the plugin data folder");
        }
        Path dataFolder = pluginDataFolder.toAbsolutePath().normalize();
        Path databaseFile = dataFolder.resolve(relative).normalize();
        if (!databaseFile.startsWith(dataFolder)) {
            throw new IllegalArgumentException(
                    "database.sqlite.file must stay inside the plugin data folder");
        }
        return databaseFile;
    }

    private List<Arena> parseArenas(YamlConfiguration source) {
        List<Arena> result = new ArrayList<>();
        Set<String> occupiedStarts = new HashSet<>();
        Set<String> arenaIds = new HashSet<>();
        int index = 0;
        for (Map<?, ?> data : source.getMapList("startPositions")) {
            index++;
            String id = string(data, "id", "arena-" + index);
            if (!ArenaId.isValid(id) || !arenaIds.add(id)) {
                throw new IllegalArgumentException(
                        "Arena IDs must be safe, lowercase, and unique");
            }
            Location start = parseLocation(data, "startPositions[" + (index - 1) + "]");
            String startKey = start.getWorld().getUID()
                    + ":" + start.getBlockX() + ":" + start.getBlockY() + ":" + start.getBlockZ();
            if (!occupiedStarts.add(startKey)) {
                throw new IllegalArgumentException("Duplicate arena start at " + startKey);
            }

            Location exit = null;
            if (booleanValue(data, "useCustomEndPosition", false)) {
                Object rawExit = data.get("endPos");
                if (!(rawExit instanceof Map<?, ?> exitData)) {
                    throw new IllegalArgumentException(id + " enables a custom exit but has no endPos section");
                }
                exit = parseLocation(exitData, id + ".endPos");
            }
            result.add(new Arena(id, start, exit));
        }
        return List.copyOf(result);
    }

    private Location parseLocation(Map<?, ?> data, String description) {
        String worldName = string(data, "world", null);
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException(description + " has no world");
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalArgumentException(description + " refers to unloaded world '" + worldName + "'");
        }
        double x = number(data, "x");
        double y = number(data, "y");
        double z = number(data, "z");
        float yaw = (float) number(data, "yaw", 0.0);
        float pitch = (float) number(data, "pitch", 0.0);
        Location location = new Location(world, x, y, z, yaw, pitch);
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            throw new IllegalArgumentException(description + " is outside the world's build height");
        }
        if (!world.getWorldBorder().isInside(location)) {
            throw new IllegalArgumentException(description + " is outside the world border");
        }
        return location;
    }

    private List<Material> parseParkourBlocks(YamlConfiguration source) {
        List<Material> result = new ArrayList<>();
        for (String name : source.getStringList("parkourBlocks")) {
            Material material = Material.matchMaterial(name);
            if (!isSafeParkourMaterial(material)) {
                throw new IllegalArgumentException(
                        "Unsafe parkour block material '" + name
                                + "'; use a solid, stable, non-burning, non-stateful full block");
            }
            result.add(material);
        }
        return List.copyOf(result);
    }

    static boolean isSafeParkourMaterial(Material material) {
        BlockType blockType = material == null ? null : material.asBlockType();
        return blockType != null
                && blockType.hasItemType()
                && !blockType.isAir()
                && blockType.isSolid()
                && blockType.hasCollision()
                && blockType.isOccluding()
                && !blockType.hasGravity()
                && !blockType.isBurnable()
                && !blockType.isFlammable()
                && !ParkourMaterialPolicy.isStatefulOrWorkstation(material.name())
                && blockType != BlockType.TNT
                && blockType != BlockType.RESPAWN_ANCHOR
                && blockType != BlockType.END_PORTAL_FRAME;
    }

    private static void validateArenaLayout(RuntimeSettings settings) {
        List<ArenaBounds> bounds = new ArrayList<>();
        List<Arena> arenas = settings.arenas();
        for (int index = 0; index < arenas.size(); index++) {
            Arena arena = arenas.get(index);
            ArenaBounds arenaBounds = ArenaBounds.around(
                    arena,
                    settings.horizontalRadius(),
                    settings.fallDistance());
            World world = Objects.requireNonNull(arena.start().getWorld(), "Arena world");

            if (arenaBounds.minY() < world.getMinHeight() || arenaBounds.maxY() >= world.getMaxHeight()) {
                throw new IllegalArgumentException(
                        "Arena '" + arena.id() + "' course/fall bounds exceed the world build height");
            }
            if (!isInsideWorldBorder(world, arenaBounds)) {
                throw new IllegalArgumentException(
                        "Arena '" + arena.id() + "' course bounds exceed the world border");
            }
            if (!ArenaBounds.hasClearHeadroom(arena.baseBlock().getBlock())) {
                throw new IllegalArgumentException(
                        "Arena '" + arena.id() + "' start requires two clear air blocks of headroom");
            }

            for (int previousIndex = 0; previousIndex < bounds.size(); previousIndex++) {
                if (arenaBounds.overlaps(bounds.get(previousIndex))) {
                    throw new IllegalArgumentException(
                            "Arena '" + arena.id() + "' overlaps arena '"
                                    + arenas.get(previousIndex).id() + "' including safety margins");
                }
            }
            bounds.add(arenaBounds);
        }

        for (Arena arena : arenas) {
            if (arena.exit() != null) {
                validateCustomExit(arena, bounds);
            }
        }
    }

    private static void validateCustomExit(Arena arena, List<ArenaBounds> arenaBounds) {
        Location exit = Objects.requireNonNull(arena.exit(), "arena exit");
        for (ArenaBounds bounds : arenaBounds) {
            if (bounds.contains(exit)) {
                throw new IllegalArgumentException(
                        "Arena '" + arena.id() + "' custom exit is inside a protected arena volume");
            }
        }
        SafePlayerExit.Inspection inspection = SafePlayerExit.inspect(exit);
        if (!inspection.safe()) {
            throw new IllegalArgumentException(
                    "Arena '" + arena.id() + "' custom exit is unsafe ("
                            + inspection.reason().name().toLowerCase(Locale.ROOT) + ')');
        }
    }

    private static boolean isInsideWorldBorder(World world, ArenaBounds bounds) {
        double y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1.0, bounds.maxY()));
        return world.getWorldBorder().isInside(new Location(world, bounds.minX() + 0.5, y, bounds.minZ() + 0.5))
                && world.getWorldBorder().isInside(new Location(world, bounds.minX() + 0.5, y, bounds.maxZ() + 0.5))
                && world.getWorldBorder().isInside(new Location(world, bounds.maxX() + 0.5, y, bounds.minZ() + 0.5))
                && world.getWorldBorder().isInside(new Location(world, bounds.maxX() + 0.5, y, bounds.maxZ() + 0.5));
    }

    private List<RewardTier> parseRewardTiers(YamlConfiguration source) {
        List<RewardTier> result = new ArrayList<>();
        for (Map<?, ?> data : source.getMapList("finishCommands")) {
            int minimum = integer(data, "minScore", Integer.MIN_VALUE);
            int maximum = integer(data, "maxScore", Integer.MAX_VALUE);
            Object rawCommands = data.get("commands");
            if (!(rawCommands instanceof List<?> commands)) {
                throw new IllegalArgumentException("Every finishCommands entry must contain a commands list");
            }
            List<String> sanitized = new ArrayList<>();
            for (Object rawCommand : commands) {
                String command = String.valueOf(rawCommand).strip();
                if (command.isEmpty()) {
                    continue;
                }
                if (command.length() > 2048 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Unsafe finish command in score range " + minimum + "-" + maximum);
                }
                sanitized.add(command);
            }
            result.add(new RewardTier(minimum, maximum, sanitized));
        }
        return List.copyOf(result);
    }

    private Particle parseParticle(String configuredName) {
        String normalized = configuredName.strip().toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "TOTEM" -> "TOTEM_OF_UNDYING";
            case "ANVIL_USE" -> "SMOKE";
            default -> normalized;
        };
        try {
            return requireParticleWithoutData(Particle.valueOf(normalized), configuredName);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid particle type '" + configuredName + "'", exception);
        }
    }

    private static Sound parseSound(String configuredName) {
        if (configuredName == null || configuredName.isBlank()) {
            throw new IllegalArgumentException("Milestone sound must be a nonblank registry key");
        }
        String configuredKey = configuredName.strip().toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(
                configuredKey.indexOf(':') >= 0 ? configuredKey : "minecraft:" + configuredKey);
        Sound sound = key == null ? null : Registry.SOUND_EVENT.get(key);
        if (sound == null) {
            throw new IllegalArgumentException(
                    "Invalid milestone sound '" + configuredName + "'");
        }
        return sound;
    }

    static Particle requireParticleWithoutData(Particle particle, String configuredName) {
        Objects.requireNonNull(particle, "particle");
        Objects.requireNonNull(configuredName, "configuredName");
        if (particle.getDataType() != Void.class) {
            throw new IllegalArgumentException(
                    "Particle type '" + configuredName + "' requires unsupported typed data");
        }
        return particle;
    }

    private static void requireGuiItem(String configuredName, String path) {
        String normalized = configuredName.strip().toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "SIGN" -> "OAK_SIGN";
            case "WOOD_DOOR" -> "OAK_DOOR";
            default -> normalized;
        };
        Material material = Material.matchMaterial(normalized);
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Invalid GUI item material at " + path + ": '" + configuredName + "'");
        }
    }

    private static void validateKnownKeys(
            YamlConfiguration source,
            ValidationAccumulator problems) {
        boolean explicitConfigVersion = source.contains("configVersion", true);
        if (!explicitConfigVersion) {
            problems.warning("configVersion is not declared; treating this as a legacy configuration");
        }
        long unknown = source.getKeys(true).stream()
                .filter(key -> !KNOWN_CONFIG_KEYS.contains(key))
                .count();
        if (unknown > 0L) {
            if (explicitConfigVersion) {
                problems.error(unknown + " unknown config key(s) are present; remove typos or obsolete settings");
            } else {
                problems.warning(unknown + " legacy/unknown config key(s) are present and ignored");
            }
        }
    }

    private void validatePrimitiveSettings(
            YamlConfiguration source,
            ValidationAccumulator problems) {
        Integer configVersion = scalarInteger(source, "configVersion", problems);
        if (configVersion != null && configVersion != 2) {
            problems.error("configVersion must be 2");
        }
        Double fallDistance = scalarNumber(source, "gameplay.fallDistance", problems);
        if (fallDistance != null
                && (fallDistance < ArenaBounds.MINIMUM_FALL_DISTANCE || fallDistance > 64.0)) {
            problems.error("gameplay.fallDistance must be between 6 and 64");
        }
        Integer horizontalRadius = scalarInteger(source, "gameplay.horizontalRadius", problems);
        if (horizontalRadius != null && (horizontalRadius < 3 || horizontalRadius > 64)) {
            problems.error("gameplay.horizontalRadius must be between 3 and 64");
        }
        Integer maximumSeconds = scalarInteger(source, "gameplay.maximumRunSeconds", problems);
        if (maximumSeconds != null && (maximumSeconds < 30 || maximumSeconds > 86_400)) {
            problems.error("gameplay.maximumRunSeconds must be between 30 and 86400");
        }
        Integer idleSeconds = scalarInteger(source, "gameplay.idleTimeoutSeconds", problems);
        if (idleSeconds != null && (idleSeconds < 15
                || (maximumSeconds != null && idleSeconds > maximumSeconds))) {
            problems.error("gameplay.idleTimeoutSeconds must be at least 15 and no greater than the run maximum");
        }
        Integer particleCount = scalarInteger(source, "particle.count", problems);
        if (particleCount != null && (particleCount < 0 || particleCount > 1000)) {
            problems.error("particle.count must be between 0 and 1000");
        }
        for (String path : List.of(
                "gameplay.onlyReplaceAir",
                "particle.show",
                "runFinishCommands",
                "rewards.onlyOnPersonalBest",
                "queue.enabled",
                "milestones.enabled",
                "categories.combo.enabled",
                "antiCheat.enabled",
                "antiCheat.blockProjectiles",
                "antiCheat.blockRiptide",
                "antiCheat.blockExploitTeleports")) {
            requireBoolean(source.get(path), path, problems);
        }
        Object rawMilestoneScores = source.get("milestones.scores");
        if (!(rawMilestoneScores instanceof List<?> scores)) {
            problems.error("milestones.scores must be a list");
        } else {
            Set<Integer> uniqueScores = new HashSet<>();
            if (scores.size() > 64) {
                problems.error("milestones.scores must not exceed 64 entries");
            }
            for (Object rawScore : scores) {
                if (!(rawScore instanceof Number number)
                        || number.doubleValue() != number.intValue()
                        || number.intValue() < 1
                        || number.intValue() > 1_000_000
                        || !uniqueScores.add(number.intValue())) {
                    problems.error(
                            "milestones.scores must contain unique integers between 1 and 1000000");
                    break;
                }
            }
        }
        Object rawMilestoneSound = source.get("milestones.sound");
        if (!(rawMilestoneSound instanceof String soundName)
                || soundName.isBlank()) {
            problems.error("milestones.sound must be a nonblank sound registry key");
        } else {
            try {
                parseSound(soundName);
            } catch (IllegalArgumentException exception) {
                problems.error("milestones.sound does not name a supported sound");
            }
        }
        Object rawMilestoneParticle = source.get("milestones.particle");
        if (!(rawMilestoneParticle instanceof String milestoneParticle)
                || milestoneParticle.isBlank()) {
            problems.error("milestones.particle must be a nonblank particle name");
        } else {
            try {
                parseParticle(milestoneParticle);
            } catch (IllegalArgumentException exception) {
                problems.error(
                        "milestones.particle does not name a supported untyped particle");
            }
        }
        validateIntegerRange(
                source,
                "milestones.particleCount",
                0,
                1_000,
                problems);
        validateIntegerRange(
                source,
                "milestones.cooldownSeconds",
                0,
                60,
                problems);
        validateIntegerRange(
                source,
                "categories.combo.maximumGapSeconds",
                1,
                60,
                problems);
        validateIntegerRange(
                source,
                "antiCheat.minimumJumpIntervalMillis",
                0,
                2_000,
                problems);
        validateIntegerRange(
                source,
                "antiCheat.anomalyAuditCooldownSeconds",
                0,
                600,
                problems);
        Integer joinCooldown = scalarInteger(source, "queue.joinCooldownSeconds", problems);
        Integer readinessWindow = scalarInteger(source, "queue.readinessWindowSeconds", problems);
        Integer reminderInterval = scalarInteger(source, "queue.reminderIntervalSeconds", problems);
        if (joinCooldown != null && (joinCooldown < 0 || joinCooldown > 600)) {
            problems.error("queue.joinCooldownSeconds must be between 0 and 600");
        }
        if (readinessWindow != null && (readinessWindow < 5 || readinessWindow > 300)) {
            problems.error("queue.readinessWindowSeconds must be between 5 and 300");
        }
        if (reminderInterval != null && (reminderInterval < 2 || reminderInterval > 60
                || readinessWindow != null && reminderInterval > readinessWindow)) {
            problems.error("queue.reminderIntervalSeconds must be 2 through 60 and no greater than readiness");
        }
        Object rawSelectionPolicy = source.get("arenaSelection.policy");
        ArenaSelectionPolicy parsedPolicy = null;
        if (!(rawSelectionPolicy instanceof String selectionPolicy) || selectionPolicy.isBlank()) {
            problems.error("arenaSelection.policy must be a supported policy name");
        } else {
            try {
                parsedPolicy = ArenaSelectionPolicy.parse(selectionPolicy);
            } catch (IllegalArgumentException exception) {
                problems.error("arenaSelection.policy is not supported");
            }
        }
        Object rawPinnedArena = source.get("arenaSelection.pinnedArena");
        if (!(rawPinnedArena instanceof String pinnedArena)) {
            problems.error("arenaSelection.pinnedArena must be a string");
        } else if (!pinnedArena.isBlank() && !ArenaId.isValid(pinnedArena)) {
            problems.error("arenaSelection.pinnedArena must be a safe arena ID");
        } else if (parsedPolicy == ArenaSelectionPolicy.PINNED && pinnedArena.isBlank()) {
            problems.error("PINNED arena selection requires arenaSelection.pinnedArena");
        }
        Object particleName = source.get("particle.type");
        if (!(particleName instanceof String name) || name.isBlank()) {
            problems.error("particle.type must be a nonblank material name");
        } else {
            try {
                parseParticle(name);
            } catch (IllegalArgumentException exception) {
                problems.error("particle.type does not name a supported particle");
            }
        }
    }

    private static void validateIntegerRange(
            YamlConfiguration source,
            String path,
            int minimum,
            int maximum,
            ValidationAccumulator problems) {
        Integer value = scalarInteger(source, path, problems);
        if (value != null && (value < minimum || value > maximum)) {
            problems.error(path + " must be between " + minimum + " and " + maximum);
        }
    }

    private void validateArenaEntries(
            YamlConfiguration source,
            ValidationAccumulator problems) {
        Object rawArenas = source.get("startPositions");
        if (!(rawArenas instanceof List<?> arenas)) {
            problems.error("startPositions must be a list");
            return;
        }
        if (arenas.isEmpty()) {
            problems.error("At least one arena is required");
            return;
        }
        if (arenas.size() > MAXIMUM_ARENAS) {
            problems.error("At most " + MAXIMUM_ARENAS + " arenas may be configured");
            return;
        }

        List<Arena> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> starts = new HashSet<>();
        boolean requireExplicitIds = source.contains("configVersion", true)
                && source.getInt("configVersion", 0) >= 2;
        boolean strictUnknownKeys = source.contains("configVersion", true);
        for (int index = 0; index < arenas.size(); index++) {
            Object rawArena = arenas.get(index);
            String description = "startPositions[" + index + ']';
            if (!(rawArena instanceof Map<?, ?> arenaData)) {
                problems.error(description + " must be a mapping");
                continue;
            }
            validateNestedMapKeys(
                    arenaData, ARENA_ENTRY_KEYS, description, strictUnknownKeys, problems);
            Object rawExit = arenaData.get("endPos");
            Map<?, ?> exitData = rawExit instanceof Map<?, ?> mapping ? mapping : null;
            if (exitData != null) {
                validateNestedMapKeys(
                        exitData,
                        LOCATION_ENTRY_KEYS,
                        description + ".endPos",
                        strictUnknownKeys,
                        problems);
            } else if (arenaData.containsKey("endPos")) {
                problems.error(description + ".endPos must be a mapping when present");
            }
            if (!(arenaData.get("id") instanceof String configuredId)
                    || configuredId.isBlank()) {
                String message = description + " has no explicit stable arena ID; its index-derived ID may shift";
                if (requireExplicitIds) {
                    problems.error(message);
                } else {
                    problems.warning(message);
                }
            }
            String id = string(arenaData, "id", "arena-" + (index + 1));
            if (!ArenaId.isValid(id)) {
                problems.error(description + " has an unsafe arena ID");
                continue;
            }
            if (!ids.add(id)) {
                problems.error("Arena ID '" + id + "' is duplicated");
                continue;
            }

            Location start = validatedLocation(arenaData, description, problems);
            Location exit = null;
            Object rawCustomExit = arenaData.get("useCustomEndPosition");
            if (arenaData.containsKey("useCustomEndPosition")) {
                requireBoolean(rawCustomExit, description + ".useCustomEndPosition", problems);
            }
            boolean customExit = rawCustomExit instanceof Boolean enabled && enabled;
            if (customExit) {
                if (exitData == null) {
                    if (!arenaData.containsKey("endPos")) {
                        problems.error(description + " enables a custom exit but has no endPos mapping");
                    }
                } else {
                    exit = validatedLocation(exitData, description + ".endPos", problems);
                }
            }
            if (start == null || (customExit && exit == null)) {
                continue;
            }
            String startKey = Objects.requireNonNull(start.getWorld(), "world").getUID()
                    + ":" + start.getBlockX() + ":" + start.getBlockY() + ":" + start.getBlockZ();
            if (!starts.add(startKey)) {
                problems.error(description + " duplicates another arena's start block");
                continue;
            }
            parsed.add(new Arena(id, start, exit));
        }

        Double fallDistance = scalarNumber(source, "gameplay.fallDistance", new ValidationAccumulator());
        Integer horizontalRadius = scalarInteger(
                source,
                "gameplay.horizontalRadius",
                new ValidationAccumulator());
        if (fallDistance == null || horizontalRadius == null) {
            return;
        }
        List<ArenaBounds> bounds = new ArrayList<>();
        List<Arena> boundedArenas = new ArrayList<>();
        for (Arena arena : parsed) {
            ArenaBounds current;
            try {
                current = ArenaBounds.around(arena, horizontalRadius, fallDistance);
            } catch (IllegalArgumentException | ArithmeticException exception) {
                problems.error("Arena '" + arena.id() + "' has invalid safety bounds");
                continue;
            }
            World world = Objects.requireNonNull(arena.start().getWorld(), "arena world");
            if (current.minY() < world.getMinHeight() || current.maxY() >= world.getMaxHeight()) {
                problems.error("Arena '" + arena.id() + "' safety bounds exceed build height");
            }
            if (!isInsideWorldBorder(world, current)) {
                problems.error("Arena '" + arena.id() + "' safety bounds exceed the world border");
            }
            if (!ArenaBounds.hasClearHeadroom(arena.baseBlock().getBlock())) {
                problems.error("Arena '" + arena.id() + "' needs two clear air blocks of headroom");
            }
            for (int previousIndex = 0; previousIndex < bounds.size(); previousIndex++) {
                if (current.overlaps(bounds.get(previousIndex))) {
                    problems.error("Arena '" + arena.id() + "' overlaps arena '"
                            + boundedArenas.get(previousIndex).id() + "' including safety margins");
                }
            }
            bounds.add(current);
            boundedArenas.add(arena);
        }
        for (Arena arena : boundedArenas) {
            if (arena.exit() == null) {
                continue;
            }
            try {
                validateCustomExit(arena, bounds);
            } catch (IllegalArgumentException exception) {
                problems.error(exception.getMessage());
            } catch (RuntimeException exception) {
                problems.error("Arena '" + arena.id() + "' custom exit could not be inspected safely");
            }
        }
    }

    private static Location validatedLocation(
            Map<?, ?> values,
            String description,
            ValidationAccumulator problems) {
        Object rawWorld = values.get("world");
        if (!(rawWorld instanceof String worldName) || worldName.isBlank()) {
            problems.error(description + " must name a world");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            problems.error(description + " refers to a world that is not loaded");
            return null;
        }
        Double x = mapNumber(values, "x", description, problems);
        Double y = mapNumber(values, "y", description, problems);
        Double z = mapNumber(values, "z", description, problems);
        Double yaw = mapOptionalNumber(values, "yaw", 0.0, description, problems);
        Double pitch = mapOptionalNumber(values, "pitch", 0.0, description, problems);
        if (x == null || y == null || z == null || yaw == null || pitch == null) {
            return null;
        }
        Location location = new Location(world, x, y, z, yaw.floatValue(), pitch.floatValue());
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            problems.error(description + " is outside the world's build height");
        }
        if (!world.getWorldBorder().isInside(location)) {
            problems.error(description + " is outside the world border");
        }
        return location;
    }

    private static Double mapNumber(
            Map<?, ?> values,
            String key,
            String description,
            ValidationAccumulator problems) {
        if (!values.containsKey(key)) {
            problems.error(description + " is missing numeric " + key);
            return null;
        }
        Object raw = values.get(key);
        Double parsed = finiteNumber(raw);
        if (parsed == null) {
            problems.error(description + '.' + key + " must be a finite number");
        }
        return parsed;
    }

    private static Double mapOptionalNumber(
            Map<?, ?> values,
            String key,
            double fallback,
            String description,
            ValidationAccumulator problems) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        Object raw = values.get(key);
        Double parsed = finiteNumber(raw);
        if (parsed == null) {
            problems.error(description + '.' + key + " must be a finite number");
        }
        return parsed;
    }

    private static void validateParkourBlockEntries(
            YamlConfiguration source,
            ValidationAccumulator problems) {
        Object rawBlocks = source.get("parkourBlocks");
        if (!(rawBlocks instanceof List<?> blocks)) {
            problems.error("parkourBlocks must be a list");
            return;
        }
        if (blocks.isEmpty()) {
            problems.error("At least one parkour block is required");
        }
        for (int index = 0; index < blocks.size(); index++) {
            Object rawBlock = blocks.get(index);
            if (!(rawBlock instanceof String name) || !isSafeParkourMaterial(Material.matchMaterial(name))) {
                problems.error("parkourBlocks[" + index + "] is not a safe platform material");
            }
        }
    }

    private void validateRewardEntries(
            YamlConfiguration source,
            ValidationAccumulator problems) {
        Set<String> allowedRoots = null;
        try {
            allowedRoots = RewardCommandRootPolicy.parse(
                    source.get("rewards.allowedCommandRoots"));
        } catch (IllegalArgumentException exception) {
            problems.error(exception.getMessage());
        }
        Object rawTiers = source.get("finishCommands");
        if (!(rawTiers instanceof List<?> tiers)) {
            problems.error("finishCommands must be a list");
            return;
        }
        boolean rewardsEnabled = source.getBoolean("runFinishCommands", false);
        boolean strictUnknownKeys = source.contains("configVersion", true);
        List<int[]> ranges = new ArrayList<>();
        List<ConfigurationShapePolicy.RewardStepRange> rewardStepRanges = new ArrayList<>();
        for (int tierIndex = 0; tierIndex < tiers.size(); tierIndex++) {
            Object rawTier = tiers.get(tierIndex);
            String tierPath = "finishCommands[" + tierIndex + ']';
            if (!(rawTier instanceof Map<?, ?> tier)) {
                problems.error(tierPath + " must be a mapping");
                continue;
            }
            validateNestedMapKeys(
                    tier, REWARD_TIER_KEYS, tierPath, strictUnknownKeys, problems);
            Integer minimum = mapInteger(tier, "minScore", Integer.MIN_VALUE, tierPath, problems);
            Integer maximum = mapInteger(tier, "maxScore", Integer.MAX_VALUE, tierPath, problems);
            if (minimum != null && maximum != null) {
                if (minimum > maximum) {
                    problems.error(tierPath + " minimum score exceeds its maximum score");
                } else {
                    for (int[] range : ranges) {
                        if (minimum <= range[1] && maximum >= range[0]) {
                            problems.warning(tierPath + " overlaps another reward range; both plans will run");
                            break;
                        }
                    }
                    ranges.add(new int[] {minimum, maximum});
                }
            }
            Object rawCommands = tier.get("commands");
            if (!(rawCommands instanceof List<?> commands)) {
                problems.error(tierPath + ".commands must be a list");
                continue;
            }
            if (commands.isEmpty()) {
                problems.warning(tierPath + " has no reward commands");
            }
            int nonEmptyCommandCount = 0;
            for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
                String path = tierPath + ".commands[" + commandIndex + ']';
                Object rawCommand = commands.get(commandIndex);
                if (!(rawCommand instanceof String configuredCommand)) {
                    problems.error(path + " must be a string");
                    continue;
                }
                String command = configuredCommand.strip();
                if (command.isEmpty()) {
                    problems.warning(path + " is empty and will be ignored");
                    continue;
                }
                nonEmptyCommandCount++;
                if (command.length() > 2048 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                    problems.error(path + " is not a safe single-line command");
                    continue;
                }
                String root;
                try {
                    root = RewardCommandRootPolicy.rootOf(command);
                } catch (IllegalArgumentException exception) {
                    problems.error(path + " has an unsafe command root");
                    continue;
                }
                if (allowedRoots != null && !allowedRoots.contains(root)) {
                    problems.error(path + " uses command root '" + root
                            + "' outside rewards.allowedCommandRoots");
                    continue;
                }
                if (plugin.getServer().getCommandMap().getCommand(root) == null) {
                    String message = path + " requires unavailable command root '" + root + "'";
                    if (rewardsEnabled) {
                        problems.error(message);
                    } else {
                        problems.warning(message + " (rewards are disabled)");
                    }
                }
            }
            if (minimum != null
                    && maximum != null
                    && minimum <= maximum
                    && nonEmptyCommandCount > 0) {
                rewardStepRanges.add(
                        new ConfigurationShapePolicy.RewardStepRange(
                                minimum, maximum, nonEmptyCommandCount));
            }
        }
        validateMaximumRewardPlanSize(rewardStepRanges, problems);
    }

    private static void validateNestedMapKeys(
            Map<?, ?> values,
            Set<String> allowedKeys,
            String description,
            boolean strict,
            ValidationAccumulator problems) {
        ConfigurationShapePolicy.unknownKeys(values, allowedKeys, description, strict)
                .ifPresent(problem -> {
                    if (problem.error()) {
                        problems.error(problem.message());
                    } else {
                        problems.warning(problem.message());
                    }
                });
    }

    private static void validateMaximumRewardPlanSize(
            List<ConfigurationShapePolicy.RewardStepRange> rewardStepRanges,
            ValidationAccumulator problems) {
        long maximumMatchingSteps =
                ConfigurationShapePolicy.maximumMatchingRewardSteps(rewardStepRanges);
        if (maximumMatchingSteps > RewardPlanRequest.MAX_STEPS) {
            problems.error(
                    "finishCommands can produce " + maximumMatchingSteps
                            + " matching commands for one score; durable reward plans allow at most "
                            + RewardPlanRequest.MAX_STEPS + " steps");
        }
    }

    private static void validatePermissionEntries(
            YamlConfiguration source,
            ValidationAccumulator problems) {
        for (String name : List.of(
                "openGui",
                "leaveArena",
                "playGame",
                "reload",
                "statsCmd",
                "topCmd",
                "info",
                "help",
                "preferences",
                "admin",
                "adminOpen",
                "adminDebug",
                "adminStop",
                "adminRecover",
                "adminValidate",
                "adminArena",
                "adminQueue",
                "adminSeason",
                "adminExport",
                "adminReward",
                "adminInvestigate")) {
            Object value = source.get("permissions." + name);
            if (!(value instanceof String permission)
                    || permission.isBlank()
                    || permission.codePoints().anyMatch(Character::isWhitespace)) {
                problems.error("permissions." + name + " must be a nonblank node without spaces");
            }
        }
        try {
            parsePermissionSettings(source);
        } catch (IllegalArgumentException exception) {
            problems.error(exception.getMessage());
        }
    }

    private void validateDatabaseEntries(
            YamlConfiguration source,
            ValidationAccumulator problems,
            boolean verifyStorage) {
        Object configuredType = source.get("database.type");
        if (!(configuredType instanceof String type) || !"SQLITE".equalsIgnoreCase(type)) {
            problems.error("database.type must be the string SQLITE");
        }
        if (source.getBoolean("mysql.enabled", false)
                || source.getBoolean("database.mysql.enabled", false)) {
            problems.error("Legacy MySQL storage must remain disabled");
        }

        Object configuredFile = source.get("database.sqlite.file");
        if (!(configuredFile instanceof String fileName) || fileName.isBlank()) {
            problems.error("database.sqlite.file must be a nonblank relative file name");
        } else {
            try {
                if (verifyStorage) {
                    SqlitePathGuard.resolve(dataFolder, fileName);
                } else {
                    resolveConfinedDatabasePath(dataFolder, fileName);
                }
            } catch (IllegalArgumentException exception) {
                problems.error(exception.getMessage());
            }
        }
        Integer timeout = scalarInteger(source, "database.sqlite.busyTimeoutMillis", problems);
        if (timeout != null && (timeout <= 0 || timeout > 600_000)) {
            problems.error("database.sqlite.busyTimeoutMillis must be between 1 and 600000");
        }
        Integer backupRetention = scalarInteger(
                source, "database.sqlite.migrationBackupRetention", problems);
        if (backupRetention != null
                && (backupRetention < DatabaseSettings.MINIMUM_MIGRATION_BACKUP_RETENTION
                        || backupRetention > DatabaseSettings.MAXIMUM_MIGRATION_BACKUP_RETENTION)) {
            problems.error("database.sqlite.migrationBackupRetention must be between "
                    + DatabaseSettings.MINIMUM_MIGRATION_BACKUP_RETENTION + " and "
                    + DatabaseSettings.MAXIMUM_MIGRATION_BACKUP_RETENTION);
        }
    }

    private static void validateTranslationEntries(
            YamlConfiguration source,
            ValidationAccumulator problems) {
        for (String path : List.of(
                "scoreboardRecordInChat",
                "chat",
                "mainGui",
                "mainGui.tutorialItem",
                "mainGui.playItem",
                "mainGui.scoreboardItem",
                "mainGui.playerItem",
                "mainGui.backItem",
                "mainGui.closeItem")) {
            if (source.getConfigurationSection(path) == null) {
                problems.error("translations.yml is missing section " + path);
            }
        }

        for (String path : TRANSLATION_STRING_PATHS) {
            requireTranslationString(source, path, problems);
            validateTranslationPlaceholderSyntax(source, path, problems);
        }
        for (String path : TRANSLATION_STRING_LIST_PATHS) {
            requireTranslationStringList(source, path, problems);
            validateTranslationPlaceholderSyntax(source, path, problems);
        }
        for (String path : TRANSLATION_BOOLEAN_PATHS) {
            requireBoolean(source.get(path), "translations.yml " + path, problems);
        }

        validateTranslationItem(source, "mainGui.tutorialItem.item", problems);
        validateTranslationItem(source, "mainGui.playItem.item", problems);
        validateTranslationItem(source, "mainGui.scoreboardItem.item", problems);
        validateTranslationItem(source, "mainGui.playerItem.item", problems);
        validateTranslationItem(source, "mainGui.backItem.item", problems);
        validateTranslationItem(source, "mainGui.closeItem.item", problems);
        requireTranslationItemType(
                source,
                "mainGui.playerItem.item",
                Material.PLAYER_HEAD,
                problems);
        if (Boolean.TRUE.equals(source.get("mainGui.useFillItem"))) {
            validateTranslationPane(source, "mainGui.fillItem", problems);
        }
        Object configuredTitleColor = source.get("mainGui.titleColor", true);
        if (!hasValidExplicitGuiTitleColor(configuredTitleColor)) {
            problems.error("translations.yml mainGui.titleColor must use #RRGGBB notation");
        }

        requireTranslationPlaceholders(
                source, "scoreboardRecordInChat.record", problems, "playerName", "score");
        requireAnyTranslationPlaceholder(
                source, "scoreboardRecordInChat.record", problems, "rank", "index");
        requireTranslationPlaceholders(source, "chat.playerNotFound", problems, "playerName");
        for (String path : List.of(
                "chat.noPermissionGui",
                "chat.noPermissionLeave",
                "chat.noPermissionPlay",
                "chat.noPermissionReload",
                "chat.noPermissionTop",
                "chat.noPermissionStats",
                "chat.noPermission")) {
            recommendTranslationPlaceholders(source, path, problems, "permissionName");
        }
        requireTranslationPlaceholders(source, "chat.queueJoined", problems, "position");
        requireTranslationPlaceholders(source, "chat.queueAlready", problems, "position");
        requireTranslationPlaceholders(source, "chat.queueReady", problems, "seconds");
        requireTranslationPlaceholders(
                source, "chat.queuePositionActionbar", problems, "position", "total");
        requireTranslationPlaceholders(source, "chat.queueNotReady", problems, "position");
        requireTranslationPlaceholders(source, "chat.queueCooldown", problems, "seconds");
        requireTranslationPlaceholders(
                source,
                "chat.chatStats",
                problems,
                "playerPlace",
                "totalPlaces",
                "playerScore",
                "percentile");
        requireTranslationPlaceholderInEveryLine(source, "chat.scoreMsgs", "score", problems);
        requireTranslationPlaceholders(
                source, "mainGui.scoreboardItem.scoreboardRecord", problems, "playerName", "score");
        requireAnyTranslationPlaceholder(
                source, "mainGui.scoreboardItem.scoreboardRecord", problems, "rank", "index");
        requireTranslationPlaceholderInList(
                source, "mainGui.scoreboardItem.noPermissionLore", "permissionName", problems);
        requireTranslationPlaceholderInList(
                source, "mainGui.scoreboardItem.lore", "scoreboard", problems);
        requireTranslationPlaceholders(
                source, "mainGui.playerItem.title", problems, "playerName");
        requireTranslationPlaceholderInList(
                source, "mainGui.playerItem.noPermissionLore", "permissionName", problems);
        for (String placeholder : List.of(
                "playerScore",
                "playerPlace",
                "totalPlaces",
                "percentile")) {
            requireTranslationPlaceholderInList(
                    source, "mainGui.playerItem.lore", placeholder, problems);
        }
    }

    static boolean hasValidExplicitGuiTitleColor(Object configuredTitleColor) {
        return !(configuredTitleColor instanceof String titleColor)
                || titleColor.matches("#[0-9a-fA-F]{6}");
    }

    private static void validateTranslationItem(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        if (!(raw instanceof String configuredName)) {
            return;
        }
        try {
            requireGuiItem(configuredName, path);
        } catch (IllegalArgumentException exception) {
            problems.error("translations.yml " + path + " is not a valid GUI item");
        }
    }

    private static void requireTranslationItemType(
            YamlConfiguration source,
            String path,
            Material required,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        if (raw instanceof String configuredName
                && Material.matchMaterial(configuredName) != required) {
            problems.error("translations.yml " + path + " must be " + required.name());
        }
    }

    private static void validateTranslationPane(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        if (!(raw instanceof String configuredName)) {
            return;
        }
        try {
            requireGuiItem(configuredName, path);
            String normalized = configuredName.strip()
                    .replace(' ', '_')
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            if (!normalized.equals("GLASS_PANE")
                    && !normalized.endsWith("_STAINED_GLASS_PANE")) {
                problems.error("translations.yml " + path + " must be a glass pane item");
            }
        } catch (IllegalArgumentException exception) {
            problems.error("translations.yml " + path + " is not a valid GUI item");
        }
    }

    private static String requireTranslationString(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        if (!(raw instanceof String value)) {
            problems.error("translations.yml " + path + " must be a string");
            return null;
        }
        return value;
    }

    private static List<String> requireTranslationStringList(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        if (!(raw instanceof List<?> values)) {
            problems.error("translations.yml " + path + " must be a list of strings");
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof String line) {
                parsed.add(line);
            } else {
                problems.error("translations.yml " + path + '[' + index + "] must be a string");
            }
        }
        return List.copyOf(parsed);
    }

    private static void requireTranslationPlaceholders(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems,
            String... placeholders) {
        Object raw = source.get(path);
        if (!(raw instanceof String value)) {
            return;
        }
        for (String placeholder : placeholders) {
            if (!value.contains(placeholder(placeholder))) {
                problems.error("translations.yml " + path + " must contain " + placeholder(placeholder));
            }
        }
    }

    private static void recommendTranslationPlaceholders(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems,
            String... placeholders) {
        Object raw = source.get(path);
        if (!(raw instanceof String value)) {
            return;
        }
        for (String placeholder : placeholders) {
            if (!value.contains(placeholder(placeholder))) {
                problems.warning("translations.yml " + path + " does not show "
                        + placeholder(placeholder) + "; the legacy message remains usable");
            }
        }
    }

    private static void requireAnyTranslationPlaceholder(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems,
            String... placeholders) {
        Object raw = source.get(path);
        if (!(raw instanceof String value)) {
            return;
        }
        for (String placeholder : placeholders) {
            if (value.contains(placeholder(placeholder))) {
                return;
            }
        }
        problems.error("translations.yml " + path + " must contain one of "
                + String.join(", ", java.util.Arrays.stream(placeholders)
                        .map(ConfigurationManager::placeholder)
                        .toList()));
    }

    private static void requireTranslationPlaceholderInEveryLine(
            YamlConfiguration source,
            String path,
            String placeholder,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        if (!(raw instanceof List<?> values)) {
            return;
        }
        if (values.isEmpty()) {
            problems.error("translations.yml " + path + " must contain at least one message");
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof String line && !line.contains(placeholder(placeholder))) {
                problems.error("translations.yml " + path + '[' + index + "] must contain "
                        + placeholder(placeholder));
            }
        }
    }

    private static void requireTranslationPlaceholderInList(
            YamlConfiguration source,
            String path,
            String placeholder,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        if (!(raw instanceof List<?> values)) {
            return;
        }
        String expected = placeholder(placeholder);
        if (values.stream().noneMatch(value -> value instanceof String line && line.contains(expected))) {
            problems.error("translations.yml " + path + " must contain " + expected);
        }
    }

    private static String placeholder(String name) {
        return "{{" + name + "}}";
    }

    private static void validateTranslationPlaceholderSyntax(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        Set<String> allowed = allowedTranslationPlaceholders(path);
        if (raw instanceof String value) {
            validateTranslationPlaceholderSyntax(value, path, allowed, problems);
            return;
        }
        if (raw instanceof List<?> values) {
            for (int index = 0; index < values.size(); index++) {
                Object value = values.get(index);
                if (value instanceof String line) {
                    validateTranslationPlaceholderSyntax(
                            line,
                            path + '[' + index + ']',
                            allowed,
                            problems);
                }
            }
        }
    }

    private static void validateTranslationPlaceholderSyntax(
            String value,
            String path,
            Set<String> allowed,
            ValidationAccumulator problems) {
        java.util.regex.Matcher matcher = TRANSLATION_PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!allowed.contains(name)) {
                problems.error("translations.yml " + path + " contains unsupported placeholder "
                        + placeholder(name));
            }
        }
        String withoutValidPlaceholders = matcher.replaceAll("");
        if (withoutValidPlaceholders.contains("{{") || withoutValidPlaceholders.contains("}}")) {
            problems.error("translations.yml " + path + " contains a malformed placeholder");
        }
    }

    static Set<String> allowedTranslationPlaceholders(String path) {
        return switch (path) {
            case "scoreboardRecordInChat.record",
                    "mainGui.scoreboardItem.scoreboardRecord" ->
                Set.of("index", "rank", "playerName", "score");
            case "mainGui.tutorialItem.lore" -> Set.of("platformBlock");
            case "mainGui.playerItem.title" -> Set.of("playerName");
            case "mainGui.playerItem.lore" ->
                Set.of("playerScore", "playerPlace", "totalPlaces", "percentile");
            case "chat.playerNotFound" -> Set.of("playerName");
            case "chat.noPermissionGui",
                    "chat.noPermissionLeave",
                    "chat.noPermissionPlay",
                    "chat.noPermissionReload",
                    "chat.noPermissionTop",
                    "chat.noPermissionStats",
                    "chat.noPermission",
                    "mainGui.scoreboardItem.noPermissionLore",
                    "mainGui.playerItem.noPermissionLore" ->
                Set.of("permissionName");
            case "chat.queueJoined", "chat.queueAlready", "chat.queueNotReady" ->
                Set.of("position");
            case "chat.queueReady", "chat.queueCooldown" -> Set.of("seconds");
            case "chat.queuePositionActionbar" -> Set.of("position", "total");
            case "chat.chatStats" ->
                Set.of("playerPlace", "totalPlaces", "playerScore", "percentile");
            case "chat.scoreMsgs" -> Set.of("score");
            case "mainGui.scoreboardItem.lore" -> Set.of("scoreboard");
            default -> Set.of();
        };
    }

    private static void requireBoolean(
            Object value,
            String path,
            ValidationAccumulator problems) {
        if (!(value instanceof Boolean)) {
            problems.error(path + " must be true or false");
        }
    }

    private static Double scalarNumber(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        Double parsed = raw instanceof Number ? finiteNumber(raw) : null;
        if (parsed == null) {
            problems.error(path + " must be a finite number");
        }
        return parsed;
    }

    private static Integer scalarInteger(
            YamlConfiguration source,
            String path,
            ValidationAccumulator problems) {
        Object raw = source.get(path);
        Double parsed = raw instanceof Number ? finiteNumber(raw) : null;
        if (parsed == null || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE
                || parsed != Math.rint(parsed)) {
            problems.error(path + " must be an integer");
            return null;
        }
        return parsed.intValue();
    }

    private static Integer mapInteger(
            Map<?, ?> values,
            String key,
            int fallback,
            String description,
            ValidationAccumulator problems) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        Object raw = values.get(key);
        Double parsed = finiteNumber(raw);
        if (parsed == null || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE
                || parsed != Math.rint(parsed)) {
            problems.error(description + '.' + key + " must be an integer");
            return null;
        }
        return parsed.intValue();
    }

    private static Double finiteNumber(Object value) {
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                parsed = Double.parseDouble(text);
            } catch (NumberFormatException exception) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(parsed) ? parsed : null;
    }

    private static final class ValidationAccumulator {
        private final Set<String> errors = new LinkedHashSet<>();
        private final Set<String> warnings = new LinkedHashSet<>();

        void error(String message) {
            errors.add(message);
        }

        void warning(String message) {
            warnings.add(message);
        }

        ConfigurationValidationReport report(String fingerprint) {
            return new ConfigurationValidationReport(
                    List.copyOf(errors),
                    List.copyOf(warnings),
                    fingerprint);
        }
    }


    private static String string(Map<?, ?> data, String key, String fallback) {
        Object value = data.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean booleanValue(Map<?, ?> data, String key, boolean fallback) {
        Object value = data.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static double number(Map<?, ?> data, String key) {
        if (!data.containsKey(key) || data.get(key) == null) {
            throw new IllegalArgumentException("Missing numeric value '" + key + "'");
        }
        return number(data, key, 0.0);
    }

    private static double number(Map<?, ?> data, String key, double fallback) {
        if (!data.containsKey(key)) {
            return fallback;
        }
        Object value = data.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Invalid numeric value for '" + key + "'");
        }
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else {
            try {
                parsed = Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid numeric value for '" + key + "'", exception);
            }
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Numeric value for '" + key + "' must be finite");
        }
        return parsed;
    }

    private static int integer(Map<?, ?> data, String key, int fallback) {
        double value = number(data, key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE || value != Math.rint(value)) {
            throw new IllegalArgumentException("Invalid integer value for '" + key + "'");
        }
        return (int) value;
    }
}
