package com.mrfdev.walktheplank.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Staged arena editing with main-thread validation and worker-only durable persistence.
 *
 * <p>Callers capture files on the operations worker, prepare an edit on the primary thread,
 * commit it on the operations worker, then return to the primary thread to revalidate and publish.
 * A failed publication can restore the exact backup on the same worker.</p>
 */
public final class ArenaConfigurationEditor {
    private final ConfigurationManager validator;
    private final AtomicConfigFile atomicFile;

    public ArenaConfigurationEditor(JavaPlugin plugin, ConfigurationManager validator) {
        Objects.requireNonNull(plugin, "plugin");
        this.validator = Objects.requireNonNull(validator, "validator");
        Path configFile = plugin.getDataFolder().toPath().resolve("config.yml");
        this.atomicFile = new AtomicConfigFile(configFile);
    }

    public boolean contains(ConfigurationManager.ConfigurationFiles files, String id)
            throws IOException {
        String safeId = ArenaId.requireValid(id);
        return find(arenaEntries(validator.editableConfig(files)), safeId) != null;
    }

    public EditPreparation prepareCreate(
            ConfigurationManager.ConfigurationFiles files,
            long expectedGeneration,
            String id,
            LocationData location) throws IOException {
        String safeId = ArenaId.requireValid(id);
        LocationData checkedLocation = Objects.requireNonNull(location, "location");
        return prepare(files, expectedGeneration, entries -> {
            if (find(entries, safeId) != null) {
                throw new IllegalArgumentException("Arena '" + safeId + "' already exists");
            }
            Map<String, Object> arena = new LinkedHashMap<>();
            arena.put("id", safeId);
            arena.putAll(locationValues(checkedLocation, true));
            arena.put("useCustomEndPosition", false);
            entries.add(arena);
        });
    }

    public EditPreparation prepareSetStart(
            ConfigurationManager.ConfigurationFiles files,
            long expectedGeneration,
            String id,
            LocationData location) throws IOException {
        String safeId = ArenaId.requireValid(id);
        LocationData checkedLocation = Objects.requireNonNull(location, "location");
        return prepare(files, expectedGeneration, entries -> {
            Map<String, Object> arena = requireArena(entries, safeId);
            arena.put("id", safeId);
            arena.putAll(locationValues(checkedLocation, true));
        });
    }

    public EditPreparation prepareSetExit(
            ConfigurationManager.ConfigurationFiles files,
            long expectedGeneration,
            String id,
            LocationData location) throws IOException {
        String safeId = ArenaId.requireValid(id);
        LocationData checkedLocation = Objects.requireNonNull(location, "location");
        return prepare(files, expectedGeneration, entries -> {
            Map<String, Object> arena = requireArena(entries, safeId);
            arena.put("id", safeId);
            arena.put("useCustomEndPosition", true);
            arena.put("endPos", locationValues(checkedLocation, false));
        });
    }

    public EditPreparation prepareClearExit(
            ConfigurationManager.ConfigurationFiles files,
            long expectedGeneration,
            String id) throws IOException {
        String safeId = ArenaId.requireValid(id);
        return prepare(files, expectedGeneration, entries -> {
            Map<String, Object> arena = requireArena(entries, safeId);
            arena.put("id", safeId);
            arena.put("useCustomEndPosition", false);
            arena.remove("endPos");
        });
    }

    public EditPreparation prepareRemove(
            ConfigurationManager.ConfigurationFiles files,
            long expectedGeneration,
            String id) throws IOException {
        String safeId = ArenaId.requireValid(id);
        return prepare(files, expectedGeneration, entries -> entries.remove(requireArena(entries, safeId)));
    }

    /** Worker-only CAS persistence. */
    public synchronized CommittedEdit commit(PreparedEdit prepared) throws IOException {
        PreparedEdit checked = Objects.requireNonNull(prepared, "prepared");
        validator.requireFilesUnchanged(checked.source());
        validator.requireDatabaseStorageSafe(checked.snapshot().databaseSettings());
        AtomicConfigFile.CommitToken token = atomicFile.replaceWithBackup(
                checked.source().configBytes(), checked.contents());
        try {
            validator.requireTranslationsUnchanged(checked.source());
        } catch (IOException failure) {
            try {
                atomicFile.restoreBackup(token);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        return new CommittedEdit(checked, token);
    }

    /** Worker-only rollback, refused if the committed file was externally changed. */
    public synchronized void rollback(CommittedEdit committed) throws IOException {
        CommittedEdit checked = Objects.requireNonNull(committed, "committed");
        atomicFile.restoreBackup(checked.commitToken);
    }

    /** Worker-only final CAS check before or immediately after runtime publication. */
    public synchronized void requireCommittedUnchanged(CommittedEdit committed)
            throws IOException {
        CommittedEdit checked = Objects.requireNonNull(committed, "committed");
        atomicFile.requireCommittedUnchanged(checked.commitToken);
        validator.requireTranslationsUnchanged(checked.prepared().source());
    }

    private EditPreparation prepare(
            ConfigurationManager.ConfigurationFiles files,
            long expectedGeneration,
            ArenaMutation mutation) throws IOException {
        ConfigurationManager.ConfigurationFiles checked =
                Objects.requireNonNull(files, "files");
        if (expectedGeneration < 0L) {
            throw new IllegalArgumentException("expectedGeneration cannot be negative");
        }
        YamlConfiguration candidate = validator.editableConfig(checked);
        List<Map<String, Object>> entries = arenaEntries(candidate);
        mutation.apply(entries);
        candidate.set("startPositions", entries);
        ConfigurationValidationReport validation = validator.validateCandidate(candidate, checked);
        if (!validation.valid()) {
            return new EditPreparation(validation, Optional.empty());
        }
        ConfigurationManager.ConfigurationSnapshot snapshot =
                validator.prepareCandidate(candidate, checked);
        PreparedEdit prepared = new PreparedEdit(
                checked,
                candidate.saveToString(),
                snapshot,
                validation,
                expectedGeneration);
        return new EditPreparation(validation, Optional.of(prepared));
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

    private static Map<String, Object> locationValues(
            LocationData location,
            boolean blockAligned) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("x", blockAligned ? location.blockX() : location.x());
        values.put("y", blockAligned ? location.blockY() : location.y());
        values.put("z", blockAligned ? location.blockZ() : location.z());
        values.put("pitch", location.pitch());
        values.put("yaw", location.yaw());
        values.put("world", location.world());
        return values;
    }

    @FunctionalInterface
    private interface ArenaMutation {
        void apply(List<Map<String, Object>> entries);
    }

    public record EditPreparation(
            ConfigurationValidationReport validation,
            Optional<PreparedEdit> prepared) {
        public EditPreparation {
            Objects.requireNonNull(validation, "validation");
            prepared = Objects.requireNonNull(prepared, "prepared");
            if (validation.valid() != prepared.isPresent()) {
                throw new IllegalArgumentException(
                        "A valid edit must have exactly one prepared candidate");
            }
        }
    }

    public record PreparedEdit(
            ConfigurationManager.ConfigurationFiles source,
            String contents,
            ConfigurationManager.ConfigurationSnapshot snapshot,
            ConfigurationValidationReport validation,
            long expectedGeneration) {
        public PreparedEdit {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(contents, "contents");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(validation, "validation");
            if (!validation.valid()) {
                throw new IllegalArgumentException("Prepared edit must be valid");
            }
            if (expectedGeneration < 0L) {
                throw new IllegalArgumentException("expectedGeneration cannot be negative");
            }
        }
    }

    public static final class CommittedEdit {
        private final PreparedEdit prepared;
        private final AtomicConfigFile.CommitToken commitToken;

        private CommittedEdit(
                PreparedEdit prepared,
                AtomicConfigFile.CommitToken commitToken) {
            this.prepared = Objects.requireNonNull(prepared, "prepared");
            this.commitToken = Objects.requireNonNull(commitToken, "commitToken");
        }

        public PreparedEdit prepared() {
            return prepared;
        }
    }

    /** Immutable scalar location captured from a Player on the primary thread. */
    public record LocationData(
            String world,
            double x,
            double y,
            double z,
            int blockX,
            int blockY,
            int blockZ,
            float yaw,
            float pitch) {
        public LocationData {
            world = Objects.requireNonNull(world, "world");
            if (world.isBlank()) {
                throw new IllegalArgumentException("world cannot be blank");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("location values must be finite");
            }
        }

        public static LocationData capture(Location location) {
            Location checked = Objects.requireNonNull(location, "location");
            World world = Objects.requireNonNull(checked.getWorld(), "location world");
            return new LocationData(
                    world.getName(),
                    checked.getX(),
                    checked.getY(),
                    checked.getZ(),
                    checked.getBlockX(),
                    checked.getBlockY(),
                    checked.getBlockZ(),
                    checked.getYaw(),
                    checked.getPitch());
        }
    }
}
