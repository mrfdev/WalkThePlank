package com.mrfdev.walktheplank.ops;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable, privacy-bounded JSONL audit trail with restart-verified SHA-256 chaining.
 *
 * <p>The state sidecar detects truncation, the anchor preserves continuity when old rotated
 * files are pruned, and every retained line authenticates the exact preceding hash. This is
 * tamper-evident rather than cryptographically signed: an operator with filesystem access can
 * replace both the log and its sidecars. Raw commands, paths, credentials, chat, and nested data
 * are deliberately rejected.</p>
 */
public final class StructuredAuditLog {
    private static final long DEFAULT_MAXIMUM_BYTES = 10L * 1024L * 1024L;
    private static final int DEFAULT_RETAINED_FILES = 10;
    private static final int MAXIMUM_FIELDS = 32;
    private static final int MAXIMUM_VALUE_LENGTH = 256;
    private static final String ZERO_HASH = "0".repeat(64);
    private static final String PRUNING_SUFFIX = ".pruning";
    private static final Pattern EVENT_PATTERN =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0,47}");
    private static final Pattern CHAINED_LINE = Pattern.compile(
            "^(\\{.*),\"audit_chain\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12})\",\"audit_sequence\":([0-9]+),"
                    + "\"previous_hash\":\"([0-9a-f]{64})\","
                    + "\"record_hash\":\"([0-9a-f]{64})\"}$");
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "raw_command",
            "command_text",
            "command_args",
            "password",
            "secret",
            "token",
            "filesystem",
            "path",
            "chat");
    private static final List<String> RESERVED_KEYS = List.of(
            "audit_chain",
            "audit_sequence",
            "previous_hash",
            "record_hash");

    private final Path directory;
    private final Path currentFile;
    private final Path stateFile;
    private final Path anchorFile;
    private final String release;
    private final long maximumBytes;
    private final int retainedFiles;
    private final Clock clock;

    private UUID chainId;
    private long sequence;
    private long anchorSequence;
    private String previousHash;
    private long expectedCurrentBytes;
    private boolean poisoned;

    public static StructuredAuditLog open(Path dataFolder, String release) throws IOException {
        return open(
                dataFolder,
                release,
                DEFAULT_MAXIMUM_BYTES,
                DEFAULT_RETAINED_FILES,
                Clock.systemUTC());
    }

    static StructuredAuditLog open(
            Path dataFolder,
            String release,
            long maximumBytes,
            int retainedFiles,
            Clock clock) throws IOException {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(release, "release");
        if (release.isBlank() || release.length() > 128) {
            throw new IllegalArgumentException(
                    "release must contain between 1 and 128 characters");
        }
        if (maximumBytes < 256) {
            throw new IllegalArgumentException("maximumBytes must be at least 256");
        }
        if (retainedFiles < 1 || retainedFiles > 100) {
            throw new IllegalArgumentException(
                    "retainedFiles must be between 1 and 100");
        }

        Path directory = dataFolder.resolve("audit");
        requireSafeDirectory(directory);
        StructuredAuditLog result = new StructuredAuditLog(
                directory,
                release,
                maximumBytes,
                retainedFiles,
                Objects.requireNonNull(clock, "clock"));
        result.initialize();
        return result;
    }

    private StructuredAuditLog(
            Path directory,
            String release,
            long maximumBytes,
            int retainedFiles,
            Clock clock) {
        this.directory = directory;
        currentFile = directory.resolve("audit.jsonl");
        stateFile = directory.resolve("audit-state.properties");
        anchorFile = directory.resolve("audit-anchor.properties");
        this.release = normalize(release, 128);
        this.maximumBytes = maximumBytes;
        this.retainedFiles = retainedFiles;
        this.clock = clock;
    }

    private void initialize() throws IOException {
        preflightCurrentFile(currentFile);
        requireSafeOptionalFile(stateFile, "Audit state");
        requireSafeOptionalFile(anchorFile, "Audit anchor");

        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(anchorFile, LinkOption.NOFOLLOW_LINKS)) {
                recoverInitialAnchorOnlyCheckpoint();
                return;
            }
            if (hasRetainedChainArtifact() || currentContainsChainMetadata()) {
                throw new IOException(
                        "Audit chain artifacts exist without durable state; possible tampering");
            }
            preserveLegacyCurrentFile();
            chainId = UUID.randomUUID();
            sequence = 0L;
            anchorSequence = 0L;
            previousHash = ZERO_HASH;
            Checkpoint initial = new Checkpoint(chainId, 0L, ZERO_HASH);
            persistCheckpoint(anchorFile, initial);
            persistCheckpoint(stateFile, initial);
            preflightCurrentFile(currentFile);
            expectedCurrentBytes = safeRegularFileSize(currentFile, "Audit file");
            return;
        }

        Checkpoint state = readCheckpoint(stateFile, "Audit state");
        Checkpoint anchor = readCheckpoint(anchorFile, "Audit anchor");
        if (!state.chainId().equals(anchor.chainId())
                || state.sequence() < anchor.sequence()) {
            throw new IOException("Audit chain state and retention anchor do not agree");
        }
        recoverPendingPrune(anchor);
        Checkpoint verified = verifyRetainedChain(anchor);
        if (!verified.equals(state)) {
            throw new IOException(
                    "Audit chain state does not match the retained log; possible truncation or tampering");
        }
        chainId = state.chainId();
        sequence = state.sequence();
        anchorSequence = anchor.sequence();
        previousHash = state.hash();
        expectedCurrentBytes = safeRegularFileSize(currentFile, "Audit file");
    }

    private void recoverInitialAnchorOnlyCheckpoint() throws IOException {
        Checkpoint anchor = readCheckpoint(anchorFile, "Audit anchor");
        if (anchor.sequence() != 0L
                || !ZERO_HASH.equals(anchor.hash())
                || safeRegularFileSize(currentFile, "Audit file") != 0L
                || hasRetainedChainArtifact()) {
            throw new IOException(
                    "Audit state is missing for a nonempty chain; possible tampering");
        }
        chainId = anchor.chainId();
        sequence = 0L;
        anchorSequence = 0L;
        previousHash = ZERO_HASH;
        persistCheckpoint(stateFile, anchor);
        expectedCurrentBytes = 0L;
    }

    private boolean hasRetainedChainArtifact() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(path ->
                    path.getFileName().toString().startsWith("audit-chain-"));
        }
    }

    private boolean currentContainsChainMetadata() throws IOException {
        try (BufferedReader reader =
                Files.newBufferedReader(currentFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"audit_chain\"")
                        || line.contains("\"audit_sequence\"")
                        || line.contains("\"previous_hash\"")
                        || line.contains("\"record_hash\"")) {
                    return true;
                }
            }
        }
        return false;
    }

    public void record(
            String event,
            UUID playerId,
            String arenaId,
            Map<String, ?> fields) throws IOException {
        append(prepare(event, playerId, arenaId, fields));
    }

    PreparedRecord prepare(
            String event,
            UUID playerId,
            String arenaId,
            Map<String, ?> fields) {
        validateEvent(event);
        Objects.requireNonNull(fields, "fields");
        if (fields.size() > MAXIMUM_FIELDS) {
            throw new IllegalArgumentException(
                    "audit event contains more than " + MAXIMUM_FIELDS + " fields");
        }
        Instant occurredAt = clock.instant();
        byte[] payload = encodePayload(occurredAt, event, playerId, arenaId, fields)
                .getBytes(StandardCharsets.UTF_8);
        return new PreparedRecord(occurredAt, payload);
    }

    synchronized void append(PreparedRecord prepared) throws IOException {
        if (poisoned) {
            throw new IOException(
                    "Audit writer is fail-closed after an incomplete durable append");
        }
        PreparedRecord checked = Objects.requireNonNull(prepared, "prepared");
        byte[] payload = checked.payload();

        try {
            rotateBefore(payload.length + 320, checked.occurredAt());
            requireExpectedCurrentSize();
            long nextSequence = Math.addExact(sequence, 1L);
            String payloadText = new String(payload, StandardCharsets.UTF_8);
            String recordHash = calculateHash(previousHash, payloadText);
            String suffix = "\"audit_chain\":\"" + chainId
                    + "\",\"audit_sequence\":" + nextSequence
                    + ",\"previous_hash\":\"" + previousHash
                    + "\",\"record_hash\":\"" + recordHash + "\"}\n";
            byte[] line = (payloadText + ',' + suffix).getBytes(StandardCharsets.UTF_8);
            appendAndForce(line);
            expectedCurrentBytes = Math.addExact(expectedCurrentBytes, line.length);
            sequence = nextSequence;
            previousHash = recordHash;
            persistCheckpoint(
                    stateFile,
                    new Checkpoint(chainId, sequence, previousHash));
        } catch (IOException | RuntimeException failure) {
            poisoned = true;
            throw failure;
        }
    }

    public Path currentFile() {
        return currentFile;
    }

    public synchronized AuditHealth health() {
        return new AuditHealth(
                true,
                !poisoned,
                sequence,
                anchorSequence);
    }

    private String encodePayload(
            Instant occurredAt,
            String event,
            UUID playerId,
            String arenaId,
            Map<String, ?> fields) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendStringProperty(json, "timestamp", occurredAt.toString(), false);
        appendStringProperty(json, "event", event, true);
        appendStringProperty(json, "release", release, true);
        if (playerId != null) {
            appendStringProperty(json, "player_uuid", playerId.toString(), true);
        }
        if (arenaId != null) {
            appendStringProperty(json, "arena", normalize(arenaId, 64), true);
        }
        for (Map.Entry<String, ?> entry : new TreeMap<>(fields).entrySet()) {
            validateKey(entry.getKey());
            appendScalarProperty(json, entry.getKey(), entry.getValue());
        }
        return json.toString();
    }

    private void rotateBefore(int incomingBytes, Instant occurredAt) throws IOException {
        requireExpectedCurrentSize();
        if (expectedCurrentBytes == 0L
                || expectedCurrentBytes <= maximumBytes - incomingBytes) {
            return;
        }
        Path destination = chainArchivePath(
                sequence,
                ARCHIVE_TIMESTAMP.format(occurredAt));
        move(currentFile, destination, false);
        forceDirectory(directory);
        expectedCurrentBytes = 0L;
        preflightCurrentFile(currentFile);
        pruneArchives();
    }

    private void pruneArchives() throws IOException {
        Checkpoint anchor = readCheckpoint(anchorFile, "Audit anchor");
        recoverPendingPrune(anchor);
        List<Path> archives = chainArchives();
        boolean changed = false;
        while (archives.size() > retainedFiles) {
            Path oldest = archives.removeFirst();
            Checkpoint advanced = verifyFile(oldest, anchor);
            Path pending = oldest.resolveSibling(
                    oldest.getFileName().toString() + PRUNING_SUFFIX);
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Audit prune staging file already exists");
            }
            move(oldest, pending, false);
            forceDirectory(directory);
            persistCheckpoint(anchorFile, advanced);
            anchor = advanced;
            anchorSequence = advanced.sequence();
            Files.delete(pending);
            forceDirectory(directory);
            changed = true;
        }
        if (changed) {
            forceDirectory(directory);
        }
    }

    private void recoverPendingPrune(Checkpoint anchor) throws IOException {
        List<Path> pendingFiles = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("audit-chain-")
                                && name.endsWith(".jsonl" + PRUNING_SUFFIX);
                    })
                    .forEach(pendingFiles::add);
        }
        if (pendingFiles.size() > 1) {
            throw new IOException("Multiple audit prune staging files require operator review");
        }
        if (pendingFiles.isEmpty()) {
            return;
        }

        Path pending = pendingFiles.getFirst();
        ArchiveBounds bounds = archiveBounds(pending);
        if (anchor.equals(bounds.start())) {
            String pendingName = pending.getFileName().toString();
            Path original = pending.resolveSibling(
                    pendingName.substring(0, pendingName.length() - PRUNING_SUFFIX.length()));
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Audit prune recovery would replace an existing archive");
            }
            move(pending, original, false);
            forceDirectory(directory);
            return;
        }
        if (anchor.equals(bounds.end())) {
            Files.delete(pending);
            forceDirectory(directory);
            return;
        }
        throw new IOException(
                "Audit prune staging file does not agree with the retention anchor");
    }

    private ArchiveBounds archiveBounds(Path file) throws IOException {
        requireSafeOptionalFile(file, "Audit prune staging file");
        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            firstLine = reader.readLine();
        }
        if (firstLine == null) {
            throw new IOException("Audit prune staging file is empty");
        }
        Matcher matcher = CHAINED_LINE.matcher(firstLine);
        if (!matcher.matches()) {
            throw new IOException("Audit prune staging file has an invalid first record");
        }
        try {
            UUID firstChain = UUID.fromString(matcher.group(2));
            long firstSequence = Long.parseLong(matcher.group(3));
            if (firstSequence < 1L) {
                throw new IOException("Audit prune staging sequence is invalid");
            }
            Checkpoint start =
                    new Checkpoint(firstChain, firstSequence - 1L, matcher.group(4));
            return new ArchiveBounds(start, verifyFile(file, start));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Audit prune staging metadata is invalid", exception);
        }
    }

    private Checkpoint verifyRetainedChain(Checkpoint anchor) throws IOException {
        Checkpoint current = anchor;
        for (Path archive : chainArchives()) {
            current = verifyFile(archive, current);
        }
        return verifyFile(currentFile, current);
    }

    private Checkpoint verifyFile(Path file, Checkpoint start) throws IOException {
        requireSafeOptionalFile(file, "Audit chain file");
        Checkpoint current = start;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = CHAINED_LINE.matcher(line);
                if (!matcher.matches()) {
                    throw new IOException(
                            "Audit chain contains an invalid or legacy record");
                }
                String payload = matcher.group(1);
                UUID recordChain;
                long recordSequence;
                try {
                    recordChain = UUID.fromString(matcher.group(2));
                    recordSequence = Long.parseLong(matcher.group(3));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Audit chain metadata is invalid", exception);
                }
                String recordedPreviousHash = matcher.group(4);
                String recordedHash = matcher.group(5);
                long expectedSequence;
                try {
                    expectedSequence = Math.addExact(current.sequence(), 1L);
                } catch (ArithmeticException exception) {
                    throw new IOException("Audit chain sequence overflowed", exception);
                }
                if (!recordChain.equals(current.chainId())
                        || recordSequence != expectedSequence
                        || !recordedPreviousHash.equals(current.hash())
                        || !recordedHash.equals(calculateHash(current.hash(), payload))) {
                    throw new IOException(
                            "Audit hash chain verification failed; possible tampering");
                }
                current = new Checkpoint(recordChain, recordSequence, recordedHash);
            }
        }
        return current;
    }

    private List<Path> chainArchives() throws IOException {
        List<Path> archives = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("audit-chain-") && name.endsWith(".jsonl");
                    })
                    .forEach(archives::add);
        }
        archives.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path archive : archives) {
            requireSafeOptionalFile(archive, "Audit chain archive");
        }
        return archives;
    }

    private void preserveLegacyCurrentFile() throws IOException {
        long currentBytes = safeRegularFileSize(currentFile, "Audit file");
        if (currentBytes == 0L) {
            return;
        }
        Path legacy = uniquePath(
                "audit-legacy-" + ARCHIVE_TIMESTAMP.format(clock.instant()),
                ".jsonl");
        move(currentFile, legacy, false);
        forceDirectory(directory);
    }

    private void requireExpectedCurrentSize() throws IOException {
        long actual = safeRegularFileSize(currentFile, "Audit file");
        if (actual != expectedCurrentBytes) {
            poisoned = true;
            throw new IOException(
                    "Audit file size changed outside the writer; possible truncation or tampering");
        }
    }

    private void appendAndForce(byte[] line) throws IOException {
        try (FileChannel channel = FileChannel.open(
                currentFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
    }

    private void persistCheckpoint(Path destination, Checkpoint checkpoint)
            throws IOException {
        requireSafeOptionalFile(destination, "Audit checkpoint");
        String content = "chain=" + checkpoint.chainId() + '\n'
                + "sequence=" + checkpoint.sequence() + '\n'
                + "hash=" + checkpoint.hash() + '\n';
        Path temporary = directory.resolve(
                '.' + destination.getFileName().toString() + '.'
                        + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer =
                        ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(false);
            }
            move(temporary, destination, true);
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Checkpoint readCheckpoint(Path file, String description)
            throws IOException {
        requireSafeOptionalFile(file, description);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is missing");
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != 3
                || !lines.get(0).startsWith("chain=")
                || !lines.get(1).startsWith("sequence=")
                || !lines.get(2).startsWith("hash=")) {
            throw new IOException(description + " has an invalid format");
        }
        try {
            UUID chain = UUID.fromString(lines.get(0).substring("chain=".length()));
            long sequence = Long.parseLong(
                    lines.get(1).substring("sequence=".length()));
            String hash = lines.get(2).substring("hash=".length());
            return new Checkpoint(chain, sequence, hash);
        } catch (IllegalArgumentException exception) {
            throw new IOException(description + " has invalid values", exception);
        }
    }

    private Path uniquePath(String stem, String suffix) {
        Path candidate = directory.resolve(stem + suffix);
        int counter = 1;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = directory.resolve(stem + '-' + counter + suffix);
            counter++;
        }
        return candidate;
    }

    private Path chainArchivePath(long endingSequence, String timestamp) {
        Path candidate = directory.resolve(
                "audit-chain-" + String.format("%020d", endingSequence)
                        + '-' + timestamp + ".jsonl");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Audit archive already exists for sequence " + endingSequence);
        }
        return candidate;
    }

    private static void move(Path source, Path destination, boolean replace)
            throws IOException {
        try {
            if (replace) {
                Files.move(
                        source,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException ignored) {
            if (replace) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination);
            }
        }
    }

    private static String calculateHash(String previousHash, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(previousHash.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
            byte[] result = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
        }
    }

    private static void requireSafeDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Audit directory must be a safe regular directory");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Audit directory must be a safe regular directory");
        }
    }

    private static void preflightCurrentFile(Path currentFile) throws IOException {
        requireSafeOptionalFile(currentFile, "Audit file");
        try (FileChannel channel = FileChannel.open(
                currentFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS)) {
            channel.force(false);
        }
    }

    private static void requireSafeOptionalFile(Path file, String description)
            throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(description + " must be a safe regular file");
        }
    }

    private static long safeRegularFileSize(Path file, String description)
            throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
            throw new IOException(description + " must be a safe regular file");
        }
        return attributes.size();
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void validateEvent(String event) {
        Objects.requireNonNull(event, "event");
        if (!EVENT_PATTERN.matcher(event).matches()) {
            throw new IllegalArgumentException("invalid audit event name: " + event);
        }
    }

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "audit field key");
        if (!KEY_PATTERN.matcher(key).matches() || RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("invalid audit field key: " + key);
        }
        for (String sensitivePart : SENSITIVE_KEY_PARTS) {
            if (key.contains(sensitivePart)) {
                throw new IllegalArgumentException(
                        "sensitive audit field is not allowed: " + key);
            }
        }
    }

    private static void appendStringProperty(
            StringBuilder json,
            String key,
            String value,
            boolean prependComma) {
        if (prependComma) {
            json.append(',');
        }
        json.append('"').append(key).append("\":\"");
        appendEscaped(json, value);
        json.append('"');
    }

    private static void appendScalarProperty(
            StringBuilder json,
            String key,
            Object value) {
        json.append(',').append('"').append(key).append("\":");
        if (value == null) {
            json.append("null");
            return;
        }
        if (value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            json.append(value);
            return;
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            json.append(number);
            return;
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            json.append(number);
            return;
        }
        if (value instanceof Number) {
            throw new IllegalArgumentException(
                    "audit numeric values must be finite primitive numbers");
        }
        if (value instanceof UUID
                || value instanceof Enum<?>
                || value instanceof CharSequence) {
            json.append('"');
            appendEscaped(json, normalize(value.toString(), MAXIMUM_VALUE_LENGTH));
            json.append('"');
            return;
        }
        throw new IllegalArgumentException(
                "audit values must be scalar strings, UUIDs, enums, booleans, or numbers");
    }

    private static String normalize(String value, int maximumLength) {
        String normalized = value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .strip();
        if (normalized.length() <= maximumLength) {
            return normalized;
        }
        return normalized.substring(0, maximumLength - 1) + "\u2026";
    }

    private static void appendEscaped(StringBuilder json, String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
    }

    record PreparedRecord(Instant occurredAt, byte[] payload) {
        PreparedRecord {
            Objects.requireNonNull(occurredAt, "occurredAt");
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record Checkpoint(UUID chainId, long sequence, String hash) {
        private Checkpoint {
            Objects.requireNonNull(chainId, "chainId");
            Objects.requireNonNull(hash, "hash");
            if (sequence < 0L || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid audit checkpoint");
            }
        }
    }

    private record ArchiveBounds(Checkpoint start, Checkpoint end) {
        private ArchiveBounds {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    public record AuditHealth(
            boolean verifiedAtStartup,
            boolean writerHealthy,
            long sequence,
            long anchorSequence) {
        public AuditHealth {
            if (sequence < 0L || anchorSequence < 0L || anchorSequence > sequence) {
                throw new IllegalArgumentException("Invalid audit health counters");
            }
        }
    }
}
