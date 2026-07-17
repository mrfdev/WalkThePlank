package com.mrfdev.walktheplank.recovery;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Atomic, synchronous write-ahead journal stored beneath the plugin data directory. */
public final class RestorationJournal {
    private static final String DIRECTORY_NAME = "restoration-journal";
    private static final String FILE_SUFFIX = ".pending";
    private static final String FORMAT_VERSION = "1";
    private static final int MAX_PENDING_RECORDS = 1_024;
    private static final int MAX_SNAPSHOT_BYTES = 16 * 1_024 * 1_024;
    private static final int MAX_STRUCTURE_BASE64_LENGTH = 22_369_624;
    private static final long MAX_RECORD_FILE_BYTES = 24L * 1_024L * 1_024L;
    private static final long MAX_TOTAL_RECORD_BYTES = 256L * 1_024L * 1_024L;
    private static final Pattern TEMPORARY_FILE_NAME = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{12}\\..+\\.tmp");
    private static final Set<String> RECORD_PROPERTIES = Set.of(
            "formatVersion",
            "journalId",
            "sessionId",
            "arenaId",
            "worldId",
            "worldName",
            "x",
            "y",
            "z",
            "expected.material",
            "expected.blockData",
            "original.material",
            "original.blockData",
            "original.structure",
            "original.fingerprint",
            "releaseIdentity",
            "createdAtEpochMillis",
            "recordIntegrity");

    private final Path directory;
    private final String releaseIdentity;
    private final Supplier<UUID> idSupplier;
    private final LongSupplier clock;
    private final DirectoryForcer directoryForcer;
    private final Map<UUID, RestorationRecord> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> pendingFileSizes = new LinkedHashMap<>();
    private long pendingFileBytes;

    private volatile PublishedState publishedState = PublishedState.empty();
    private volatile RestorationFailure lastFailure;

    private RestorationJournal(
            Path directory,
            String releaseIdentity,
            Supplier<UUID> idSupplier,
            LongSupplier clock,
            DirectoryForcer directoryForcer) {
        this.directory = directory;
        this.releaseIdentity = releaseIdentity;
        this.idSupplier = idSupplier;
        this.clock = clock;
        this.directoryForcer = directoryForcer;
    }

    public static RestorationJournal open(Path pluginDataDirectory, String releaseIdentity) throws IOException {
        return open(pluginDataDirectory, releaseIdentity, UUID::randomUUID, System::currentTimeMillis);
    }

    static RestorationJournal open(
            Path pluginDataDirectory,
            String releaseIdentity,
            Supplier<UUID> idSupplier,
            LongSupplier clock) throws IOException {
        return open(
                pluginDataDirectory,
                releaseIdentity,
                idSupplier,
                clock,
                RestorationJournal::forceDirectoryPath);
    }

    static RestorationJournal open(
            Path pluginDataDirectory,
            String releaseIdentity,
            Supplier<UUID> idSupplier,
            LongSupplier clock,
            DirectoryForcer directoryForcer) throws IOException {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Objects.requireNonNull(releaseIdentity, "releaseIdentity");
        Objects.requireNonNull(idSupplier, "idSupplier");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(directoryForcer, "directoryForcer");
        String normalizedRelease = releaseIdentity.strip();
        if (normalizedRelease.isEmpty()) {
            throw new IllegalArgumentException("releaseIdentity must not be blank");
        }
        if (normalizedRelease.length() > 1_024
                || normalizedRelease.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "releaseIdentity must contain at most 1024 non-control characters");
        }

        Path dataDirectory = pluginDataDirectory.toAbsolutePath().normalize();
        rejectSymbolicLinkSegments(dataDirectory);
        ensureRealDirectory(dataDirectory);
        Path journalDirectory = dataDirectory.resolve(DIRECTORY_NAME);
        ensureRealDirectory(journalDirectory);
        RestorationJournal journal = new RestorationJournal(
                journalDirectory,
                normalizedRelease,
                idSupplier,
                clock,
                directoryForcer);
        journal.cleanupStaleTemporaryFiles();
        journal.loadPendingRecords();
        journal.publishState();
        return journal;
    }

    public synchronized RestorationRecord append(
            UUID sessionId,
            String arenaId,
            UUID worldId,
            String worldName,
            int x,
            int y,
            int z,
            SerializedBlockState expectedState,
            SerializedBlockState originalState,
            byte[] originalStructure) throws IOException {
        Objects.requireNonNull(originalStructure, "originalStructure");
        if (pending.size() >= MAX_PENDING_RECORDS) {
            throw new IOException("Restoration journal reached its " + MAX_PENDING_RECORDS + " record safety limit");
        }
        if (originalStructure.length == 0 || originalStructure.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("Original block snapshot must contain 1 to "
                    + MAX_SNAPSHOT_BYTES + " bytes");
        }

        UUID journalId = Objects.requireNonNull(idSupplier.get(), "journal ID");
        RestorationRecord record = RestorationRecord.create(
                journalId,
                sessionId,
                arenaId,
                worldId,
                worldName,
                x,
                y,
                z,
                expectedState,
                originalState,
                originalStructure.clone(),
                releaseIdentity,
                clock.getAsLong());
        if (pending.containsKey(journalId)) {
            throw new IOException("Duplicate restoration journal ID " + journalId);
        }
        for (RestorationRecord existing : pending.values()) {
            if (record.isSameBlock(existing)) {
                throw new IOException("Block already has a pending restoration record: "
                        + worldId + ":" + x + ":" + y + ":" + z);
            }
        }

        Path target = recordPath(journalId);
        Path temporary = Files.createTempFile(directory, journalId + ".", ".tmp");
        boolean moved = false;
        try {
            writeRecord(temporary, record);
            long recordBytes = Files.size(temporary);
            if (recordBytes > MAX_RECORD_FILE_BYTES
                    || pendingFileBytes > MAX_TOTAL_RECORD_BYTES - recordBytes) {
                throw new IOException("Restoration journal reached its aggregate size safety limit");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("The filesystem does not support atomic restoration-journal writes", exception);
            }
            moved = true;
            pending.put(journalId, record);
            pendingFileSizes.put(journalId, recordBytes);
            pendingFileBytes += recordBytes;
            publishState();
            try {
                forceDirectory();
            } catch (IOException failure) {
                throw new RestorationJournalCommitUncertainException(record, failure);
            }
            return record;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public synchronized void complete(UUID journalId) throws IOException {
        Objects.requireNonNull(journalId, "journalId");
        Path recordFile = recordPath(journalId);
        long removedBytes = 0L;
        if (Files.exists(recordFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(
                    recordFile,
                    BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("Refusing to remove non-regular restoration record "
                        + recordFile.getFileName());
            }
            removedBytes = attributes.size();
        }
        Files.deleteIfExists(recordFile);
        forceDirectory();
        pending.remove(journalId);
        Long accountedBytes = pendingFileSizes.remove(journalId);
        pendingFileBytes = Math.max(
                0L,
                pendingFileBytes - (accountedBytes == null ? removedBytes : accountedBytes));
        publishState();
    }

    public List<RestorationRecord> pendingRecords() {
        return publishedState.records();
    }

    public int pendingCount() {
        return publishedState.recordsById().size();
    }

    synchronized long pendingFileBytes() {
        return pendingFileBytes;
    }

    public boolean isPending(UUID journalId) {
        Objects.requireNonNull(journalId, "journalId");
        return publishedState.recordsById().containsKey(journalId);
    }

    public boolean hasPendingSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return publishedState.records().stream()
                .anyMatch(record -> record.sessionId().equals(sessionId));
    }

    public Set<String> pendingArenaIds(Set<UUID> excludedSessionIds) {
        Objects.requireNonNull(excludedSessionIds, "excludedSessionIds");
        return publishedState.records().stream()
                .filter(record -> !excludedSessionIds.contains(record.sessionId()))
                .map(RestorationRecord::arenaId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Optional<RestorationFailure> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public void recordFailure(RestorationRecord record, String message) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(message, "message");
        lastFailure = new RestorationFailure(
                record.journalId(),
                record.arenaId(),
                message,
                Instant.ofEpochMilli(Math.max(0L, clock.getAsLong())));
    }

    Path directory() {
        return directory;
    }

    /**
     * Publishes a lock-free immutable view after the in-memory fail-closed state changes.
     *
     * <p>Mutation callers hold this journal's monitor. Runtime readers intentionally never acquire
     * that monitor because it also serializes write, fsync, rename, and delete operations.</p>
     */
    private void publishState() {
        List<RestorationRecord> records = pending.values().stream()
                .sorted(Comparator.comparingLong(RestorationRecord::createdAtEpochMillis)
                        .thenComparing(record -> record.journalId().toString()))
                .toList();
        publishedState = new PublishedState(Map.copyOf(pending), records);
    }

    private void loadPendingRecords() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
            stream.forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        if (files.size() > MAX_PENDING_RECORDS) {
            throw new IOException("Restoration journal contains more than "
                    + MAX_PENDING_RECORDS + " pending records");
        }

        for (Path file : files) {
            BasicFileAttributes attributes = Files.readAttributes(
                    file,
                    BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("Refusing to follow non-regular restoration record "
                        + file.getFileName());
            }
            if (attributes.size() > MAX_RECORD_FILE_BYTES) {
                throw new IOException("Restoration journal record is too large: " + file.getFileName());
            }
            if (pendingFileBytes > MAX_TOTAL_RECORD_BYTES - attributes.size()) {
                throw new IOException("Restoration journal exceeds its aggregate size safety limit");
            }
            RestorationRecord record = readRecord(file);
            String expectedFileName = record.journalId() + FILE_SUFFIX;
            if (!file.getFileName().toString().equals(expectedFileName)) {
                throw new IOException("Restoration journal filename does not match record ID: " + file.getFileName());
            }
            if (pending.putIfAbsent(record.journalId(), record) != null) {
                throw new IOException("Duplicate restoration journal ID " + record.journalId());
            }
            pendingFileSizes.put(record.journalId(), attributes.size());
            pendingFileBytes += attributes.size();
            for (RestorationRecord existing : pending.values()) {
                if (existing != record && record.isSameBlock(existing)) {
                    throw new IOException("Multiple restoration records target the same block: " + file.getFileName());
                }
            }
        }
    }

    private void cleanupStaleTemporaryFiles() throws IOException {
        boolean removed = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tmp")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                BasicFileAttributes attributes = Files.readAttributes(
                        file,
                        BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (!TEMPORARY_FILE_NAME.matcher(name).matches()
                        || !attributes.isRegularFile()
                        || attributes.isSymbolicLink()) {
                    throw new IOException(
                            "Refusing unknown or non-regular restoration temporary file " + name);
                }
                Files.delete(file);
                removed = true;
            }
        }
        if (removed) {
            forceDirectory();
        }
    }

    private void writeRecord(Path file, RestorationRecord record) throws IOException {
        Properties properties = properties(record);
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream output = Channels.newOutputStream(channel);
            properties.store(output, null);
            output.flush();
            channel.force(true);
        }
    }

    private RestorationRecord readRecord(Path file) throws IOException {
        Properties properties = new Properties();
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.READ,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                InputStream input = Channels.newInputStream(channel)) {
            properties.load(input);
        }
        if (!properties.stringPropertyNames().equals(RECORD_PROPERTIES)) {
            throw new IOException("Restoration journal record has missing or unknown properties: "
                    + file.getFileName());
        }
        if (!FORMAT_VERSION.equals(required(properties, "formatVersion", 16))) {
            throw new IOException("Unsupported restoration journal format in " + file.getFileName());
        }
        try {
            RestorationRecord record = new RestorationRecord(
                    UUID.fromString(required(properties, "journalId", 36)),
                    UUID.fromString(required(properties, "sessionId", 36)),
                    required(properties, "arenaId", 256),
                    UUID.fromString(required(properties, "worldId", 36)),
                    required(properties, "worldName", 256),
                    Integer.parseInt(required(properties, "x", 16)),
                    Integer.parseInt(required(properties, "y", 16)),
                    Integer.parseInt(required(properties, "z", 16)),
                    new SerializedBlockState(
                            required(properties, "expected.material", 128),
                            required(properties, "expected.blockData", 8_192)),
                    new SerializedBlockState(
                            required(properties, "original.material", 128),
                            required(properties, "original.blockData", 8_192)),
                    required(properties, "original.structure", MAX_STRUCTURE_BASE64_LENGTH),
                    required(properties, "original.fingerprint", 64),
                    required(properties, "releaseIdentity", 1_024),
                    Long.parseLong(required(properties, "createdAtEpochMillis", 24)));
            String expectedIntegrity = required(properties, "recordIntegrity", 64);
            if (!expectedIntegrity.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid restoration record integrity value in " + file.getFileName());
            }
            if (!recordIntegrity(record).equals(expectedIntegrity)) {
                throw new IOException("Restoration journal record integrity check failed for "
                        + file.getFileName());
            }
            return record;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid restoration journal record " + file.getFileName(), exception);
        }
    }

    private static Properties properties(RestorationRecord record) {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", FORMAT_VERSION);
        properties.setProperty("journalId", record.journalId().toString());
        properties.setProperty("sessionId", record.sessionId().toString());
        properties.setProperty("arenaId", record.arenaId());
        properties.setProperty("worldId", record.worldId().toString());
        properties.setProperty("worldName", record.worldName());
        properties.setProperty("x", Integer.toString(record.x()));
        properties.setProperty("y", Integer.toString(record.y()));
        properties.setProperty("z", Integer.toString(record.z()));
        properties.setProperty("expected.material", record.expectedState().material());
        properties.setProperty("expected.blockData", record.expectedState().blockData());
        properties.setProperty("original.material", record.originalState().material());
        properties.setProperty("original.blockData", record.originalState().blockData());
        properties.setProperty("original.structure", record.originalStructureBase64());
        properties.setProperty("original.fingerprint", record.originalFingerprint());
        properties.setProperty("releaseIdentity", record.releaseIdentity());
        properties.setProperty("createdAtEpochMillis", Long.toString(record.createdAtEpochMillis()));
        properties.setProperty("recordIntegrity", recordIntegrity(record));
        return properties;
    }

    private static String recordIntegrity(RestorationRecord record) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeCanonical(output, FORMAT_VERSION);
                writeCanonical(output, record.journalId().toString());
                writeCanonical(output, record.sessionId().toString());
                writeCanonical(output, record.arenaId());
                writeCanonical(output, record.worldId().toString());
                writeCanonical(output, record.worldName());
                output.writeInt(record.x());
                output.writeInt(record.y());
                output.writeInt(record.z());
                writeCanonical(output, record.expectedState().material());
                writeCanonical(output, record.expectedState().blockData());
                writeCanonical(output, record.originalState().material());
                writeCanonical(output, record.originalState().blockData());
                writeCanonical(output, record.originalFingerprint());
                writeCanonical(output, record.releaseIdentity());
                output.writeLong(record.createdAtEpochMillis());
            }
            return RestorationRecord.fingerprint(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not calculate restoration record integrity", exception);
        }
    }

    private static void writeCanonical(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String required(Properties properties, String key, int maximumLength) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.strip().isEmpty()) {
            throw new IOException("Missing restoration journal property " + key);
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IOException("Restoration journal property " + key
                    + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    private Path recordPath(UUID journalId) {
        return directory.resolve(journalId + FILE_SUFFIX);
    }

    private void forceDirectory() throws IOException {
        directoryForcer.force(directory);
    }

    private static void forceDirectoryPath(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void ensureRealDirectory(Path directory) throws IOException {
        if (Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory,
                    BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Restoration journal path is not a real directory: " + directory);
            }
            return;
        }
        Files.createDirectory(directory);
        Path parent = directory.getParent();
        if (parent != null) {
            forceDirectoryPath(parent);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Restoration journal path is not a real directory: " + directory);
        }
    }

    private static void rejectSymbolicLinkSegments(Path path) throws IOException {
        Path current = path.getRoot();
        if (current == null) {
            throw new IOException("Restoration journal path must be absolute: " + path);
        }
        for (Path segment : path) {
            current = current.resolve(segment);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IOException("Refusing restoration journal path containing symbolic link: " + current);
            }
        }
    }

    private record PublishedState(
            Map<UUID, RestorationRecord> recordsById,
            List<RestorationRecord> records) {
        private PublishedState {
            recordsById = Map.copyOf(Objects.requireNonNull(recordsById, "recordsById"));
            records = List.copyOf(Objects.requireNonNull(records, "records"));
        }

        private static PublishedState empty() {
            return new PublishedState(Map.of(), List.of());
        }
    }

    @FunctionalInterface
    interface DirectoryForcer {
        void force(Path directory) throws IOException;
    }
}
