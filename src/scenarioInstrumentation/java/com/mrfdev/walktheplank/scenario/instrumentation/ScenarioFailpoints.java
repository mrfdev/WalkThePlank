package com.mrfdev.walktheplank.scenario.instrumentation;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Test-only destructive boundary controller.
 *
 * <p>This class is compiled into the instrumented scenario artifact only. The production source
 * set and production JAR must never contain this class, its property prefix, or its canary.
 */
public final class ScenarioFailpoints {
    public static final String CANARY = "WTP_SCENARIO_TEST_ONLY_7E4C0D98";
    public static final String PROPERTY_PREFIX = "walktheplank.scenario.failpoint.";

    private static final String PROFILE_PROPERTY = "walktheplank.scenario.profile";
    private static final String PROFILE_ROOT_PROPERTY = "walktheplank.scenario.root";
    private static final String PROFILE_NONCE_PROPERTY = "walktheplank.scenario.nonce";
    private static final String PROFILE_MARKER = ".walktheplank-disposable-profile";
    private static final int HALT_EXIT_CODE = 97;
    private static final Duration DEFAULT_BLOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{8,128}");
    private static final PrintStream RAW_ERROR = new PrintStream(
            new FileOutputStream(FileDescriptor.err),
            true,
            StandardCharsets.UTF_8);
    private static final Set<String> STARTUP_PROPERTY_NAMES = Set.of(
            PROPERTY_PREFIX + "name",
            PROPERTY_PREFIX + "action",
            PROPERTY_PREFIX + "occurrence",
            PROPERTY_PREFIX + "nonce",
            PROPERTY_PREFIX + "block-timeout-seconds");
    private static final Set<String> POINTS = Set.of(
            "player_journal.after_temp_write",
            "player_journal.after_temp_fsync",
            "player_journal.after_rename",
            "player_journal.after_directory_fsync",
            "player_journal.after_delete",
            "restoration_journal.after_temp_write",
            "restoration_journal.after_temp_fsync",
            "restoration_journal.after_rename",
            "restoration_journal.after_directory_fsync",
            "restoration_journal.after_delete",
            "block.after_place",
            "block.after_restore",
            "teleport.after_start",
            "teleport.after_return",
            "score.after_commit",
            "reward.after_dispatch",
            "reward_claim.after_commit",
            "reward_outcome.after_commit",
            "export.csv.after_rename",
            "export.json.after_rename",
            "config.backup.after_rename",
            "config.candidate.after_rename",
            "config.after_disk_commit",
            "config.after_runtime_commit");
    /*
     * Throwing after these irreversible calls would strand disk state that the running process has
     * not yet published in memory, or would bypass the caller's rollback-publication flag. A hard
     * halt is the only faithful and safe action at these exact boundaries.
     */
    private static final Set<String> HALT_ONLY_POINTS = Set.of(
            "player_journal.after_rename",
            "player_journal.after_delete",
            "restoration_journal.after_rename",
            "restoration_journal.after_delete",
            "config.candidate.after_rename",
            "config.after_disk_commit",
            "config.after_runtime_commit");

    private static volatile Arm armed = initializeFromSystemProperties();

    private ScenarioFailpoints() {
    }

    public static synchronized void arm(
            String point,
            String action,
            int occurrence,
            String nonce) {
        requireDisposableProfile();
        String checkedPoint = requirePoint(point);
        Action checkedAction = parseAction(action);
        requireActionAllowed(checkedPoint, checkedAction);
        String checkedNonce = requireSafeToken(nonce, "nonce");
        if (occurrence < 1 || occurrence > 10_000) {
            throw new IllegalArgumentException("occurrence must be between 1 and 10000");
        }
        Arm previous = armed;
        if (previous != null) {
            previous.releaseGate().countDown();
            marker("REPLACED", previous, previous.hitCounter().get());
        }
        armed = new Arm(
                checkedPoint,
                checkedAction,
                occurrence,
                checkedNonce,
                DEFAULT_BLOCK_TIMEOUT,
                new CountDownLatch(1),
                new AtomicInteger());
        marker("ARMED", armed, 0);
    }

    public static synchronized void clear() {
        Arm current = armed;
        armed = null;
        if (current != null) {
            current.releaseGate().countDown();
            marker("CLEARED", current, current.hitCounter().get());
        }
    }

    public static synchronized void release(String nonce) {
        Arm current = armed;
        if (current == null) {
            throw new IllegalStateException("No failpoint is armed");
        }
        String checkedNonce = requireSafeToken(nonce, "nonce");
        if (!current.nonce().equals(checkedNonce)) {
            throw new SecurityException("Failpoint nonce does not match");
        }
        current.releaseGate().countDown();
        marker("RELEASED", current, current.hitCounter().get());
    }

    public static String status() {
        Arm current = armed;
        if (current == null) {
            return "clear";
        }
        int hits = current.hitCounter().get();
        return "armed point=" + current.point()
                + " action=" + current.action()
                + " occurrence=" + current.occurrence()
                + " hits=" + hits
                + " nonce=" + current.nonce();
    }

    public static Set<String> points() {
        return POINTS;
    }

    public static void hit(String point) {
        String checkedPoint = requirePoint(point);
        Arm current;
        int occurrence;
        synchronized (ScenarioFailpoints.class) {
            current = armed;
            if (current == null || !current.point().equals(checkedPoint)) {
                return;
            }
            occurrence = current.hitCounter().incrementAndGet();
            if (occurrence != current.occurrence() || armed != current) {
                return;
            }

            /*
             * Claim and begin the selected action while holding the same monitor used by arm,
             * clear, and release. Otherwise a re-arm could win after the occurrence check and an
             * obsolete HALT/THROW could still fire against the replacement generation.
             */
            marker("REACHED", current, occurrence);
            switch (current.action()) {
                case HALT -> {
                    Runtime.getRuntime().halt(HALT_EXIT_CODE);
                    throw new AssertionError("Runtime.halt unexpectedly returned");
                }
                case THROW -> {
                    armed = null;
                    current.releaseGate().countDown();
                    marker("THROWN", current, occurrence);
                    throw new ScenarioInjectedFailure(
                            "Injected scenario failure at " + checkedPoint
                                    + " occurrence " + occurrence);
                }
                case BLOCK -> {
                    // Wait outside the monitor so release, clear, and re-arm remain available.
                }
            }
        }
        block(current, occurrence);
    }

    /**
     * Preserves a boolean operand while only firing a boundary reached by a successful operation.
     */
    public static boolean accepted(boolean accepted, String point) {
        if (accepted) {
            hit(point);
        }
        return accepted;
    }

    public static void databaseCommit(String operationName) {
        Objects.requireNonNull(operationName, "operationName");
        switch (operationName) {
            case "run completion with reward intent" -> hit("score.after_commit");
            case "reward step dispatch marker" -> hit("reward_claim.after_commit");
            case "reward step completion" -> hit("reward_outcome.after_commit");
            default -> {
                // This exact operation is not a destructive-test boundary.
            }
        }
    }

    public static void exportRenamed(java.nio.file.Path destination) {
        Objects.requireNonNull(destination, "destination");
        String fileName = destination.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".csv")) {
            hit("export.csv.after_rename");
        } else if (fileName.endsWith(".json")) {
            hit("export.json.after_rename");
        } else {
            throw new IllegalStateException("Unexpected leaderboard export extension");
        }
    }

    private static void block(Arm current, int occurrence) {
        boolean released;
        try {
            released = current.releaseGate().await(
                    current.blockTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            clearIfCurrent(current);
            marker("INTERRUPTED", current, occurrence);
            throw new ScenarioInjectedFailure(
                    "Interrupted while blocking at " + current.point(), exception);
        }
        clearIfCurrent(current);
        if (!released) {
            marker("TIMED_OUT", current, occurrence);
            throw new ScenarioInjectedFailure(
                    "Timed out while blocking at " + current.point()
                            + " occurrence " + occurrence);
        }
        marker("RESUMED", current, occurrence);
    }

    private static synchronized void clearIfCurrent(Arm expected) {
        if (armed == expected) {
            armed = null;
            expected.releaseGate().countDown();
        }
    }

    private static Arm initializeFromSystemProperties() {
        try {
            return fromSystemProperties();
        } catch (RuntimeException exception) {
            RAW_ERROR.println("WTP-SCENARIO FAILPOINT CONFIG_ERROR"
                    + " type=" + exception.getClass().getSimpleName()
                    + " canary=" + CANARY);
            RAW_ERROR.flush();
            throw exception;
        }
    }

    private static Arm fromSystemProperties() {
        Set<String> configuredNames = System.getProperties()
                .stringPropertyNames()
                .stream()
                .filter(name -> name.startsWith(PROPERTY_PREFIX))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (configuredNames.isEmpty()) {
            return null;
        }
        try {
            if (!STARTUP_PROPERTY_NAMES.containsAll(configuredNames)) {
                throw new IllegalArgumentException("Unknown startup failpoint property");
            }
            requireDisposableProfile();
            String point = System.getProperty(PROPERTY_PREFIX + "name");
            String action = System.getProperty(PROPERTY_PREFIX + "action");
            String occurrence = System.getProperty(PROPERTY_PREFIX + "occurrence");
            String nonce = System.getProperty(PROPERTY_PREFIX + "nonce");
            String timeout = System.getProperty(PROPERTY_PREFIX + "block-timeout-seconds");
            if (point == null || action == null || nonce == null) {
                throw new IllegalStateException(
                        "An armed startup failpoint requires name, action, and nonce");
            }
            int checkedOccurrence = occurrence == null ? 1 : parseInteger(occurrence, "occurrence");
            int timeoutSeconds = timeout == null
                    ? Math.toIntExact(DEFAULT_BLOCK_TIMEOUT.toSeconds())
                    : parseInteger(timeout, "block-timeout-seconds");
            if (checkedOccurrence < 1 || checkedOccurrence > 10_000) {
                throw new IllegalArgumentException("occurrence must be between 1 and 10000");
            }
            if (timeoutSeconds < 1 || timeoutSeconds > 300) {
                throw new IllegalArgumentException(
                        "block-timeout-seconds must be between 1 and 300");
            }
            String checkedPoint = requirePoint(point);
            Action checkedAction = parseAction(action);
            requireActionAllowed(checkedPoint, checkedAction);
            if (timeout != null && checkedAction != Action.BLOCK) {
                throw new IllegalArgumentException(
                        "block-timeout-seconds is valid only for BLOCK");
            }
            Arm configured = new Arm(
                    checkedPoint,
                    checkedAction,
                    checkedOccurrence,
                    requireSafeToken(nonce, "nonce"),
                    Duration.ofSeconds(timeoutSeconds),
                    new CountDownLatch(1),
                    new AtomicInteger());
            marker("ARMED", configured, 0);
            return configured;
        } finally {
            configuredNames.forEach(System::clearProperty);
        }
    }

    private static int parseInteger(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static Action parseAction(String value) {
        Objects.requireNonNull(value, "action");
        try {
            return Action.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "action must be HALT, THROW, or BLOCK", exception);
        }
    }

    private static String requirePoint(String value) {
        Objects.requireNonNull(value, "point");
        String checked = value.strip().toLowerCase(Locale.ROOT);
        if (!POINTS.contains(checked)) {
            throw new IllegalArgumentException("Unknown scenario failpoint");
        }
        return checked;
    }

    private static void requireDisposableProfile() {
        if (!"true".equalsIgnoreCase(System.getProperty(PROFILE_PROPERTY, ""))) {
            throw new SecurityException(
                    "Failpoint control requires the explicit disposable scenario profile");
        }
        String nonce = requireSafeToken(
                System.getProperty(PROFILE_NONCE_PROPERTY), "profile nonce");
        String configuredRoot = System.getProperty(PROFILE_ROOT_PROPERTY);
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new SecurityException("Disposable scenario root is missing");
        }
        try {
            Path requestedRoot = Path.of(configuredRoot);
            Path normalizedRoot = requestedRoot.toAbsolutePath().normalize();
            if (!requestedRoot.isAbsolute() || !requestedRoot.equals(normalizedRoot)) {
                throw new SecurityException(
                        "Disposable scenario root must be an absolute normalized path");
            }
            Path realRoot = normalizedRoot.toRealPath();
            if (!normalizedRoot.equals(realRoot)
                    || Files.isSymbolicLink(normalizedRoot)
                    || !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException(
                        "Disposable scenario root must be a real non-symlink directory");
            }
            Path workingDirectory = Path.of("")
                    .toAbsolutePath()
                    .normalize()
                    .toRealPath();
            if (!realRoot.equals(workingDirectory) || !hasDisposableLayout(realRoot)) {
                throw new SecurityException(
                        "Disposable scenario root does not match the generated server profile");
            }
            Path marker = realRoot.resolve(PROFILE_MARKER);
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) > 256
                    || !Files.readString(marker, StandardCharsets.UTF_8)
                            .equals("schema=1\nnonce=" + nonce + "\n")) {
                throw new SecurityException(
                        "Disposable scenario marker is missing or invalid");
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof SecurityException securityException) {
                throw securityException;
            }
            throw new SecurityException(
                    "Disposable scenario root could not be verified", exception);
        }
    }

    private static boolean hasDisposableLayout(Path root) {
        Path profileName = root.getFileName();
        Path controlledRoot = root.getParent();
        Path buildRoot = controlledRoot == null ? null : controlledRoot.getParent();
        if (profileName == null
                || controlledRoot == null
                || buildRoot == null
                || controlledRoot.getFileName() == null
                || buildRoot.getFileName() == null
                || !"controlled-scenarios".equals(controlledRoot.getFileName().toString())
                || !"build".equals(buildRoot.getFileName().toString())) {
            return false;
        }
        String profile = profileName.toString();
        return "two-start".equals(profile)
                || "player-assisted".equals(profile)
                || profile.startsWith("failpoint-");
    }

    private static void requireActionAllowed(String point, Action action) {
        if (action != Action.HALT && HALT_ONLY_POINTS.contains(point)) {
            throw new IllegalArgumentException(
                    "Failpoint " + point
                            + " supports HALT only after an irreversible boundary");
        }
    }

    private static String requireSafeToken(String value, String name) {
        Objects.requireNonNull(value, name);
        String checked = value.strip();
        if (!SAFE_TOKEN.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                    name + " must contain 8 to 128 safe token characters");
        }
        return checked;
    }

    private static void marker(String state, Arm current, int hits) {
        RAW_ERROR.println("WTP-SCENARIO FAILPOINT " + state
                + " point=" + current.point()
                + " action=" + current.action()
                + " occurrence=" + current.occurrence()
                + " hits=" + hits
                + " nonce=" + current.nonce()
                + " canary=" + CANARY);
        RAW_ERROR.flush();
    }

    private enum Action {
        HALT,
        THROW,
        BLOCK
    }

    private record Arm(
            String point,
            Action action,
            int occurrence,
            String nonce,
            Duration blockTimeout,
            CountDownLatch releaseGate,
            AtomicInteger hitCounter) {
        private Arm {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(blockTimeout, "blockTimeout");
            Objects.requireNonNull(releaseGate, "releaseGate");
            Objects.requireNonNull(hitCounter, "hitCounter");
        }
    }

    public static final class ScenarioInjectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ScenarioInjectedFailure(String message) {
            super(message);
        }

        ScenarioInjectedFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
