package com.mrfdev.walktheplank.recovery;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Atomic, synchronous write-ahead journal for crash-safe player state recovery. */
public final class PlayerRecoveryJournal {
    private static final String DIRECTORY_NAME = "player-recovery-journal";
    private static final String FILE_SUFFIX = ".pending";
    private static final String FORMAT_VERSION = "1";
    private static final int MAX_PENDING_RECORDS = 1_024;
    private static final long MAX_RECORD_FILE_BYTES = 16L * 1_024L;
    private static final long MAX_TOTAL_RECORD_BYTES = 8L * 1_024L * 1_024L;
    private static final Pattern UUID_FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pending");
    private static final Pattern TEMPORARY_FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\..+\\.tmp");
    private static final Set<String> RECORD_PROPERTIES = Set.of(
            "formatVersion",
            "playerId",
            "runId",
            "arenaId",
            "return.worldId",
            "return.x",
            "return.y",
            "return.z",
            "return.yaw",
            "return.pitch",
            "state.health",
            "state.foodLevel",
            "state.saturation",
            "state.exhaustion",
            "state.walkSpeed",
            "state.allowFlight",
            "state.flying",
            "state.collidable",
            "recordIntegrity");

    private final Path directory;
    private final Map<UUID, PlayerRecoveryRecord> pending = new LinkedHashMap<>();
    private final Set<UUID> blockedPlayerIds = new HashSet<>();
    private int invalidRecordCount;
    private boolean globallyUnsafe;
    private boolean writeFailed;
    private String lastFailureCode;

    private PlayerRecoveryJournal(Path directory) {
        this.directory = directory;
    }

    public static PlayerRecoveryJournal open(Path pluginDataDirectory) throws IOException {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Path dataDirectory = pluginDataDirectory.toAbsolutePath().normalize();
        rejectSymbolicLinkSegments(dataDirectory);
        ensureRealDirectory(dataDirectory);
        Path journalDirectory = dataDirectory.resolve(DIRECTORY_NAME);
        ensureRealDirectory(journalDirectory);
        PlayerRecoveryJournal journal = new PlayerRecoveryJournal(journalDirectory);
        journal.cleanupStaleTemporaryFiles();
        journal.loadPendingRecords();
        return journal;
    }

    /**
     * Writes the recovery record before gameplay is allowed to mutate the captured player state.
     * An exact retry is idempotent; conflicting ownership is rejected.
     */
    public synchronized PlayerRecoveryRecord append(PlayerRecoveryRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        PlayerRecoveryRecord existing = pending.get(record.playerId());
        if (existing != null) {
            if (existing.equals(record)) {
                return existing;
            }
            throw new IOException("Player already has a different pending recovery record");
        }
        if (!canSafelyRecord(record.playerId())) {
            throw new IOException("Player recovery journal cannot safely accept this player");
        }
        if (pending.values().stream().anyMatch(candidate -> candidate.runId().equals(record.runId()))) {
            throw new IOException("Run already has a pending player recovery record");
        }
        if (pending.size() + invalidRecordCount >= MAX_PENDING_RECORDS) {
            throw new IOException("Player recovery journal reached its record safety limit");
        }

        Path target = recordPath(record.playerId());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player recovery target already exists");
        }
        Path temporary = Files.createTempFile(directory, record.playerId() + ".", ".tmp");
        boolean moved = false;
        try {
            writeRecord(temporary, record);
            long recordBytes = Files.size(temporary);
            if (recordBytes > MAX_RECORD_FILE_BYTES || totalRecordBytes() > MAX_TOTAL_RECORD_BYTES - recordBytes) {
                throw new IOException("Player recovery journal reached its size safety limit");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Filesystem does not support atomic player recovery writes", exception);
            }
            moved = true;
            pending.put(record.playerId(), record);
            forceDirectory(directory);
            return record;
        } catch (IOException failure) {
            markWriteFailure("append_failed");
            throw failure;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Deletes only the exact player/run/arena-owned record and is idempotent after success. */
    public synchronized void complete(UUID playerId, UUID runId, String arenaId) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(arenaId, "arenaId");
        PlayerRecoveryRecord record = pending.get(playerId);
        if (record == null) {
            if (blockedPlayerIds.contains(playerId) || Files.exists(recordPath(playerId), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Player recovery record is unreadable or ownership is ambiguous");
            }
            return;
        }
        if (!record.owns(playerId, runId, arenaId)) {
            throw new IOException("Player recovery ownership does not match completion request");
        }

        Path target = recordPath(playerId);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IOException("Refusing to remove unsafe player recovery record");
                }
            }
            Files.deleteIfExists(target);
            forceDirectory(directory);
            pending.remove(playerId, record);
        } catch (IOException failure) {
            markWriteFailure("completion_failed");
            throw failure;
        }
    }

    public synchronized Optional<PlayerRecoveryRecord> pending(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.ofNullable(pending.get(playerId));
    }

    /** Whether this UUID has retained or malformed evidence that requires staff-safe recovery. */
    public synchronized boolean requiresRecovery(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return pending.containsKey(playerId) || blockedPlayerIds.contains(playerId);
    }

    public synchronized List<PlayerRecoveryRecord> pendingRecords() {
        return pending.values().stream()
                .sorted(Comparator.comparing(record -> record.playerId().toString()))
                .toList();
    }

    /** Total unresolved files, including malformed records retained for operator inspection. */
    public synchronized int pendingCount() {
        return pending.size() + invalidRecordCount;
    }

    /** Whether this player can safely begin a newly journaled run. */
    public synchronized boolean canSafelyRecord(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return !writeFailed
                && !globallyUnsafe
                && !blockedPlayerIds.contains(playerId)
                && !pending.containsKey(playerId);
    }

    public synchronized Health health() {
        return new Health(
                invalidRecordCount == 0 && !globallyUnsafe && !writeFailed,
                pending.size() + invalidRecordCount,
                invalidRecordCount,
                !globallyUnsafe && !writeFailed,
                Optional.ofNullable(lastFailureCode));
    }

    Path directory() {
        return directory;
    }

    private void loadPendingRecords() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
            stream.forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        if (files.size() > MAX_PENDING_RECORDS) {
            throw new IOException("Player recovery journal exceeds its record safety limit");
        }

        List<LoadedRecord> valid = new ArrayList<>();
        long totalBytes = 0L;
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            UUID owner = ownerFromFileName(fileName).orElse(null);
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IOException("Unsafe player recovery file type");
                }
                if (attributes.size() > MAX_RECORD_FILE_BYTES
                        || totalBytes > MAX_TOTAL_RECORD_BYTES - attributes.size()) {
                    throw new IOException("Player recovery journal exceeds its size safety limit");
                }
                totalBytes += attributes.size();
                if (owner == null) {
                    throw new IOException("Invalid player recovery filename");
                }
                PlayerRecoveryRecord record = readRecord(file);
                if (!record.playerId().equals(owner)) {
                    throw new IOException("Player recovery filename ownership mismatch");
                }
                valid.add(new LoadedRecord(file, record));
            } catch (IOException | RuntimeException invalid) {
                markInvalid(owner, "invalid_record");
            }
        }

        Map<UUID, List<LoadedRecord>> byRun = new HashMap<>();
        for (LoadedRecord loaded : valid) {
            byRun.computeIfAbsent(loaded.record().runId(), ignored -> new ArrayList<>()).add(loaded);
        }
        for (LoadedRecord loaded : valid) {
            if (byRun.get(loaded.record().runId()).size() > 1) {
                markInvalid(loaded.record().playerId(), "ambiguous_run_ownership");
                continue;
            }
            pending.put(loaded.record().playerId(), loaded.record());
        }
    }

    private void cleanupStaleTemporaryFiles() throws IOException {
        boolean removed = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tmp")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                BasicFileAttributes attributes = Files.readAttributes(
                        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!TEMPORARY_FILE_NAME.matcher(name).matches()
                        || !attributes.isRegularFile()
                        || attributes.isSymbolicLink()) {
                    throw new IOException("Refusing unknown or unsafe player recovery temporary file");
                }
                Files.delete(file);
                removed = true;
            }
        }
        if (removed) {
            forceDirectory(directory);
        }
    }

    private void writeRecord(Path file, PlayerRecoveryRecord record) throws IOException {
        Properties properties = properties(record);
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream output = Channels.newOutputStream(channel);
            properties.store(output, null);
            output.flush();
            channel.force(true);
        }
    }

    private PlayerRecoveryRecord readRecord(Path file) throws IOException {
        Properties properties = new Properties();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                InputStream input = Channels.newInputStream(channel)) {
            properties.load(input);
        }
        if (!properties.stringPropertyNames().equals(RECORD_PROPERTIES)) {
            throw new IOException("Player recovery record has missing or unknown properties");
        }
        if (!FORMAT_VERSION.equals(required(properties, "formatVersion", 16))) {
            throw new IOException("Unsupported player recovery format");
        }
        try {
            PlayerRecoveryRecord record = new PlayerRecoveryRecord(
                    UUID.fromString(required(properties, "playerId", 36)),
                    UUID.fromString(required(properties, "runId", 36)),
                    required(properties, "arenaId", 32),
                    UUID.fromString(required(properties, "return.worldId", 36)),
                    Double.parseDouble(required(properties, "return.x", 32)),
                    Double.parseDouble(required(properties, "return.y", 32)),
                    Double.parseDouble(required(properties, "return.z", 32)),
                    Float.parseFloat(required(properties, "return.yaw", 32)),
                    Float.parseFloat(required(properties, "return.pitch", 32)),
                    Double.parseDouble(required(properties, "state.health", 32)),
                    Integer.parseInt(required(properties, "state.foodLevel", 8)),
                    Float.parseFloat(required(properties, "state.saturation", 32)),
                    Float.parseFloat(required(properties, "state.exhaustion", 32)),
                    Float.parseFloat(required(properties, "state.walkSpeed", 32)),
                    strictBoolean(properties, "state.allowFlight"),
                    strictBoolean(properties, "state.flying"),
                    strictBoolean(properties, "state.collidable"));
            String expectedIntegrity = required(properties, "recordIntegrity", 64);
            if (!expectedIntegrity.matches("[0-9a-f]{64}")
                    || !integrity(record).equals(expectedIntegrity)) {
                throw new IOException("Player recovery record integrity check failed");
            }
            return record;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid player recovery record", invalid);
        }
    }

    private static Properties properties(PlayerRecoveryRecord record) {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", FORMAT_VERSION);
        properties.setProperty("playerId", record.playerId().toString());
        properties.setProperty("runId", record.runId().toString());
        properties.setProperty("arenaId", record.arenaId());
        properties.setProperty("return.worldId", record.returnWorldId().toString());
        properties.setProperty("return.x", Double.toString(record.returnX()));
        properties.setProperty("return.y", Double.toString(record.returnY()));
        properties.setProperty("return.z", Double.toString(record.returnZ()));
        properties.setProperty("return.yaw", Float.toString(record.returnYaw()));
        properties.setProperty("return.pitch", Float.toString(record.returnPitch()));
        properties.setProperty("state.health", Double.toString(record.health()));
        properties.setProperty("state.foodLevel", Integer.toString(record.foodLevel()));
        properties.setProperty("state.saturation", Float.toString(record.saturation()));
        properties.setProperty("state.exhaustion", Float.toString(record.exhaustion()));
        properties.setProperty("state.walkSpeed", Float.toString(record.walkSpeed()));
        properties.setProperty("state.allowFlight", Boolean.toString(record.allowFlight()));
        properties.setProperty("state.flying", Boolean.toString(record.flying()));
        properties.setProperty("state.collidable", Boolean.toString(record.collidable()));
        properties.setProperty("recordIntegrity", integrity(record));
        return properties;
    }

    private static String integrity(PlayerRecoveryRecord record) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeCanonical(output, FORMAT_VERSION);
                writeCanonical(output, record.playerId().toString());
                writeCanonical(output, record.runId().toString());
                writeCanonical(output, record.arenaId());
                writeCanonical(output, record.returnWorldId().toString());
                output.writeDouble(record.returnX());
                output.writeDouble(record.returnY());
                output.writeDouble(record.returnZ());
                output.writeFloat(record.returnYaw());
                output.writeFloat(record.returnPitch());
                output.writeDouble(record.health());
                output.writeInt(record.foodLevel());
                output.writeFloat(record.saturation());
                output.writeFloat(record.exhaustion());
                output.writeFloat(record.walkSpeed());
                output.writeBoolean(record.allowFlight());
                output.writeBoolean(record.flying());
                output.writeBoolean(record.collidable());
            }
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not calculate player recovery integrity", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeCanonical(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0F, 16));
            result.append(Character.forDigit(value & 0x0F, 16));
        }
        return result.toString();
    }

    private static String required(Properties properties, String key, int maximumLength) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty() || !value.equals(value.strip())) {
            throw new IOException("Missing or non-canonical player recovery property " + key);
        }
        if (value.length() > maximumLength || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("Player recovery property is outside its safe bounds: " + key);
        }
        return value;
    }

    private static boolean strictBoolean(Properties properties, String key) throws IOException {
        String value = required(properties, key, 5);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IOException("Player recovery boolean is invalid: " + key);
        };
    }

    private long totalRecordBytes() throws IOException {
        long total = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
            for (Path file : stream) {
                BasicFileAttributes attributes = Files.readAttributes(
                        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IOException("Unsafe player recovery record exists");
                }
                if (total > MAX_TOTAL_RECORD_BYTES - attributes.size()) {
                    throw new IOException("Player recovery journal exceeds its size safety limit");
                }
                total += attributes.size();
            }
        }
        return total;
    }

    private void markInvalid(UUID owner, String failureCode) {
        invalidRecordCount++;
        lastFailureCode = failureCode;
        if (owner == null) {
            globallyUnsafe = true;
        } else {
            blockedPlayerIds.add(owner);
        }
    }

    private void markWriteFailure(String failureCode) {
        writeFailed = true;
        lastFailureCode = failureCode;
    }

    private Path recordPath(UUID playerId) {
        return directory.resolve(playerId + FILE_SUFFIX);
    }

    private static Optional<UUID> ownerFromFileName(String fileName) {
        if (!UUID_FILE_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(fileName.substring(0, 36)));
    }

    private static void ensureRealDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Player recovery path is not a safe directory");
            }
            return;
        }
        Files.createDirectory(directory);
        Path parent = directory.getParent();
        if (parent != null) {
            forceDirectory(parent);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Player recovery path is not a safe directory");
        }
    }

    private static void rejectSymbolicLinkSegments(Path path) throws IOException {
        Path current = path.getRoot();
        if (current == null) {
            throw new IOException("Player recovery path must be absolute");
        }
        for (Path segment : path) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Refusing player recovery path containing a symbolic link");
            }
        }
    }

    private static void forceDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public record Health(
            boolean healthy,
            int pendingRecords,
            int invalidRecords,
            boolean writesAvailable,
            Optional<String> lastFailureCode) {
        public Health {
            if (pendingRecords < 0 || invalidRecords < 0 || invalidRecords > pendingRecords) {
                throw new IllegalArgumentException("Invalid player recovery health counts");
            }
            lastFailureCode = Objects.requireNonNull(lastFailureCode, "lastFailureCode");
        }
    }

    private record LoadedRecord(Path file, PlayerRecoveryRecord record) {
        private LoadedRecord {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(record, "record");
        }
    }
}
