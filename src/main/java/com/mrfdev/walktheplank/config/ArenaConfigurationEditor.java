package com.mrfdev.walktheplank.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Guarded arena-list editing with validation-before-write and atomic persistence. */
public final class ArenaConfigurationEditor {
    private final ConfigurationManager validator;
    private final AtomicConfigFile atomicFile;
    private final Path configFile;
    private AtomicConfigFile.CommitToken pendingRollback;

    public ArenaConfigurationEditor(JavaPlugin plugin, ConfigurationManager validator) {
        Objects.requireNonNull(plugin, "plugin");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.configFile = plugin.getDataFolder().toPath().resolve("config.yml");
        this.atomicFile = new AtomicConfigFile(configFile);
    }

    public synchronized List<ArenaSummary> arenas() throws IOException {
        List<Map<String, Object>> entries = arenaEntries(load().configuration());
        List<ArenaSummary> summaries = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = entries.get(index);
            String id = String.valueOf(entry.getOrDefault("id", "arena-" + (index + 1)));
            String world = String.valueOf(entry.getOrDefault("world", "unknown"));
            String position = coordinate(entry.get("x")) + ","
                    + coordinate(entry.get("y")) + ","
                    + coordinate(entry.get("z"));
            boolean customExit = Boolean.TRUE.equals(entry.get("useCustomEndPosition"));
            summaries.add(new ArenaSummary(id, world, position, customExit));
        }
        return List.copyOf(summaries);
    }

    public synchronized boolean contains(String id) throws IOException {
        return find(arenaEntries(load().configuration()), id) != null;
    }

    public synchronized EditResult create(String id, Location location) throws IOException {
        String safeId = ArenaId.requireValid(id);
        EditableConfig loaded = load();
        YamlConfiguration candidate = loaded.configuration();
        List<Map<String, Object>> entries = arenaEntries(candidate);
        if (find(entries, safeId) != null) {
            throw new IllegalArgumentException("Arena '" + safeId + "' already exists");
        }
        Map<String, Object> arena = new LinkedHashMap<>();
        arena.put("id", safeId);
        arena.putAll(locationValues(location, true));
        arena.put("useCustomEndPosition", false);
        entries.add(arena);
        return validateAndPersist(candidate, entries, loaded.sourceBytes());
    }

    public synchronized EditResult setStart(String id, Location location) throws IOException {
        String safeId = ArenaId.requireValid(id);
        EditableConfig loaded = load();
        YamlConfiguration candidate = loaded.configuration();
        List<Map<String, Object>> entries = arenaEntries(candidate);
        Map<String, Object> arena = requireArena(entries, safeId);
        arena.put("id", safeId);
        arena.putAll(locationValues(location, true));
        return validateAndPersist(candidate, entries, loaded.sourceBytes());
    }

    public synchronized EditResult setExit(String id, Location location) throws IOException {
        String safeId = ArenaId.requireValid(id);
        EditableConfig loaded = load();
        YamlConfiguration candidate = loaded.configuration();
        List<Map<String, Object>> entries = arenaEntries(candidate);
        Map<String, Object> arena = requireArena(entries, safeId);
        arena.put("id", safeId);
        arena.put("useCustomEndPosition", true);
        arena.put("endPos", locationValues(location, false));
        return validateAndPersist(candidate, entries, loaded.sourceBytes());
    }

    public synchronized EditResult clearExit(String id) throws IOException {
        String safeId = ArenaId.requireValid(id);
        EditableConfig loaded = load();
        YamlConfiguration candidate = loaded.configuration();
        List<Map<String, Object>> entries = arenaEntries(candidate);
        Map<String, Object> arena = requireArena(entries, safeId);
        arena.put("id", safeId);
        arena.put("useCustomEndPosition", false);
        arena.remove("endPos");
        return validateAndPersist(candidate, entries, loaded.sourceBytes());
    }

    public synchronized EditResult remove(String id) throws IOException {
        String safeId = ArenaId.requireValid(id);
        EditableConfig loaded = load();
        YamlConfiguration candidate = loaded.configuration();
        List<Map<String, Object>> entries = arenaEntries(candidate);
        Map<String, Object> arena = requireArena(entries, safeId);
        entries.remove(arena);
        return validateAndPersist(candidate, entries, loaded.sourceBytes());
    }

    public synchronized void restoreBackup() throws IOException {
        AtomicConfigFile.CommitToken commitToken = pendingRollback;
        pendingRollback = null;
        if (commitToken == null) {
            throw new IOException("No arena-editor commit is available for rollback");
        }
        atomicFile.restoreBackup(commitToken);
    }

    private EditResult validateAndPersist(
            YamlConfiguration candidate,
            List<Map<String, Object>> entries,
            byte[] sourceBytes) throws IOException {
        pendingRollback = null;
        candidate.set("startPositions", entries);
        ConfigurationValidationReport validation = validator.validateCandidate(candidate);
        if (!validation.valid()) {
            return new EditResult(false, validation);
        }
        pendingRollback = atomicFile.replaceWithBackup(sourceBytes, candidate.saveToString());
        return new EditResult(true, validation);
    }

    private EditableConfig load() throws IOException {
        Path parent = Objects.requireNonNull(configFile.getParent(), "config parent");
        if (Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(configFile)
                || !Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("config.yml is not a safe regular file");
        }
        byte[] sourceBytes = Files.readAllBytes(configFile);
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.options().parseComments(true);
        try {
            loaded.loadFromString(new String(sourceBytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            throw new IOException("config.yml is not valid YAML");
        }
        return new EditableConfig(loaded, sourceBytes);
    }

    private static List<Map<String, Object>> arenaEntries(YamlConfiguration source) {
        Object rawEntries = source.get("startPositions");
        if (!(rawEntries instanceof List<?> entries)) {
            throw new IllegalArgumentException("startPositions is not an editable list");
        }
        List<Map<String, Object>> copied = new ArrayList<>();
        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("startPositions contains a non-mapping entry");
            }
            copied.add(copyMap(entry));
        }
        return copied;
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
        }
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object element : list) {
                copy.add(copyValue(element));
            }
            return copy;
        }
        return value;
    }

    private static Map<String, Object> requireArena(
            List<Map<String, Object>> entries,
            String id) {
        Map<String, Object> arena = find(entries, id);
        if (arena == null) {
            throw new IllegalArgumentException("Arena '" + id + "' does not exist");
        }
        return arena;
    }

    private static Map<String, Object> find(
            List<Map<String, Object>> entries,
            String id) {
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = entries.get(index);
            String effectiveId = String.valueOf(entry.getOrDefault("id", "arena-" + (index + 1)));
            if (id.equals(effectiveId)) {
                return entry;
            }
        }
        return null;
    }

    private static Map<String, Object> locationValues(Location location, boolean blockAligned) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("x", blockAligned ? location.getBlockX() : location.getX());
        values.put("y", blockAligned ? location.getBlockY() : location.getY());
        values.put("z", blockAligned ? location.getBlockZ() : location.getZ());
        values.put("pitch", location.getPitch());
        values.put("yaw", location.getYaw());
        values.put("world", world.getName());
        return values;
    }

    private static String coordinate(Object value) {
        return value instanceof Number number ? Double.toString(number.doubleValue()) : "?";
    }

    public record EditResult(boolean persisted, ConfigurationValidationReport validation) {
        public EditResult {
            Objects.requireNonNull(validation, "validation");
        }
    }

    public record ArenaSummary(String id, String world, String position, boolean customExit) {
        public ArenaSummary {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
        }
    }

    private record EditableConfig(YamlConfiguration configuration, byte[] sourceBytes) {
        private EditableConfig {
            Objects.requireNonNull(configuration, "configuration");
            sourceBytes = Objects.requireNonNull(sourceBytes, "sourceBytes").clone();
        }

        @Override
        public byte[] sourceBytes() {
            return sourceBytes.clone();
        }
    }
}
