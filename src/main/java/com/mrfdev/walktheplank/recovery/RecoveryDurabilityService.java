package com.mrfdev.walktheplank.recovery;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded single writer for crash-recovery journals.
 *
 * <p>This executor is intentionally independent from audit, export, configuration, and SQLite
 * workers. It accepts immutable scalar/byte captures only; Bukkit objects must never cross this
 * boundary.</p>
 */
public final class RecoveryDurabilityService {
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final String THREAD_NAME = "walktheplank-recovery-writer";
    private static final String OWNERSHIP_LOCK_FILE = ".recovery-durability.lock";

    private final RestorationJournal restorationJournal;
    private final PlayerRecoveryJournal playerRecoveryJournal;
    private final int queueCapacity;
    private final ThreadPoolExecutor executor;
    private final Path ownershipDataDirectory;
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private final LongAdder accepted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private volatile Thread workerThread;
    private boolean ownershipReleaseAttempted;
    private boolean ownershipReleaseSucceeded;

    /**
     * Acquires exclusive lifetime ownership before opening or cleaning either recovery journal.
     */
    public static RecoveryDurabilityService open(
            Path pluginDataDirectory,
            String releaseIdentity) throws IOException {
        return open(pluginDataDirectory, releaseIdentity, DEFAULT_QUEUE_CAPACITY);
    }

    static RecoveryDurabilityService open(
            Path pluginDataDirectory,
            String releaseIdentity,
            int queueCapacity) throws IOException {
        Objects.requireNonNull(releaseIdentity, "releaseIdentity");
        Ownership ownership = acquireOwnership(pluginDataDirectory);
        try {
            RestorationJournal restorationJournal = RestorationJournal.open(
                    ownership.dataDirectory(), releaseIdentity);
            PlayerRecoveryJournal playerRecoveryJournal = PlayerRecoveryJournal.open(
                    ownership.dataDirectory());
            return new RecoveryDurabilityService(
                    restorationJournal,
                    playerRecoveryJournal,
                    queueCapacity,
                    ownership);
        } catch (IOException | RuntimeException | Error failure) {
            closeOwnership(ownership);
            throw failure;
        }
    }

    RecoveryDurabilityService(
            RestorationJournal restorationJournal,
            PlayerRecoveryJournal playerRecoveryJournal,
            Path pluginDataDirectory,
            int queueCapacity) {
        this(
                restorationJournal,
                playerRecoveryJournal,
                queueCapacity,
                acquireOwnership(
                        pluginDataDirectory,
                        queueCapacity,
                        restorationJournal,
                        playerRecoveryJournal));
    }

    private RecoveryDurabilityService(
            RestorationJournal restorationJournal,
            PlayerRecoveryJournal playerRecoveryJournal,
            int queueCapacity,
            Ownership ownership) {
        this.restorationJournal = Objects.requireNonNull(restorationJournal, "restorationJournal");
        this.playerRecoveryJournal =
                Objects.requireNonNull(playerRecoveryJournal, "playerRecoveryJournal");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        this.queueCapacity = queueCapacity;
        Ownership checkedOwnership = Objects.requireNonNull(ownership, "ownership");
        ownershipDataDirectory = checkedOwnership.dataDirectory();
        ownershipChannel = checkedOwnership.channel();
        ownershipLock = checkedOwnership.lock();
        try {
            validateJournalOwnership(checkedOwnership.dataDirectory());
            executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    task -> {
                        Thread thread = Thread.ofPlatform()
                                .name(THREAD_NAME)
                                .daemon(true)
                                .unstarted(task);
                        workerThread = thread;
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()) {
                @Override
                protected void terminated() {
                    try {
                        releaseOwnership();
                    } finally {
                        super.terminated();
                    }
                }
            };
        } catch (RuntimeException | Error failure) {
            closeOwnership(checkedOwnership);
            throw failure;
        }
    }

    public RestorationJournal restorationJournal() {
        return restorationJournal;
    }

    public PlayerRecoveryJournal playerRecoveryJournal() {
        return playerRecoveryJournal;
    }

    public RestorationPreparation prepareRestoration(
            UUID sessionId,
            String arenaId,
            UUID worldId,
            String worldName,
            int x,
            int y,
            int z,
            SerializedBlockState expectedState,
            SerializedBlockState originalState,
            byte[] originalStructure) {
        RestorationPreparation preparation = new RestorationPreparation(
                this,
                new RestorationCapture(
                        sessionId,
                        arenaId,
                        worldId,
                        worldName,
                        x,
                        y,
                        z,
                        expectedState,
                        originalState,
                        originalStructure));
        submitRequired(() -> {
            preparation.persist();
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                preparation.submissionFailed(unwrap(failure));
            }
        });
        return preparation;
    }

    public PlayerRecoveryPreparation preparePlayerRecovery(PlayerRecoveryRecord record) {
        PlayerRecoveryPreparation preparation =
                new PlayerRecoveryPreparation(this, Objects.requireNonNull(record, "record"));
        submitRequired(() -> {
            preparation.persist();
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                preparation.submissionFailed(unwrap(failure));
            }
        });
        return preparation;
    }

    public CompletableFuture<Void> completeRestoration(UUID journalId) {
        UUID checkedId = Objects.requireNonNull(journalId, "journalId");
        return submitRequired(() -> {
            restorationJournal.complete(checkedId);
            return null;
        });
    }

    public CompletableFuture<Void> completePlayerRecovery(
            UUID playerId,
            UUID runId,
            String arenaId) {
        UUID checkedPlayerId = Objects.requireNonNull(playerId, "playerId");
        UUID checkedRunId = Objects.requireNonNull(runId, "runId");
        String checkedArenaId = Objects.requireNonNull(arenaId, "arenaId");
        return submitRequired(() -> {
            playerRecoveryJournal.complete(checkedPlayerId, checkedRunId, checkedArenaId);
            return null;
        });
    }

    public Status status() {
        return new Status(
                queueCapacity,
                executor.getQueue().size(),
                executor.getActiveCount(),
                accepted.sum(),
                completed.sum(),
                failed.sum(),
                rejected.sum(),
                executor.isShutdown(),
                executor.isTerminated());
    }

    /**
     * Waits for every operation accepted before this barrier to finish without stopping the
     * writer. This is reserved for lifecycle gates such as an explicit plugin reload; gameplay
     * paths must observe completion asynchronously.
     */
    public boolean flush(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (Thread.currentThread() == workerThread) {
            return false;
        }
        CompletableFuture<Void> barrier = submitRequired(() -> null);
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            nanos = Long.MAX_VALUE;
        }
        try {
            barrier.get(nanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stops acceptance and drains accepted journal operations without requiring a main-thread
     * callback. Queued futures become terminal if the timeout expires.
     */
    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        executor.shutdown();
        if (Thread.currentThread() == workerThread) {
            return false;
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            nanos = Long.MAX_VALUE;
        }
        try {
            if (!executor.awaitTermination(nanos, TimeUnit.NANOSECONDS)) {
                rejectQueued("Recovery durability close timed out");
                if (!executor.awaitTermination(nanos, TimeUnit.NANOSECONDS)) {
                    return false;
                }
            }
            return releaseOwnership();
        } catch (InterruptedException interrupted) {
            rejectQueued("Recovery durability close was interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private <T> CompletableFuture<T> submitRequired(Callable<T> operation) {
        RequiredTask<T> task = new RequiredTask<>(Objects.requireNonNull(operation, "operation"));
        try {
            executor.execute(task);
            accepted.increment();
        } catch (RejectedExecutionException rejection) {
            rejected.increment();
            task.reject(rejection);
        }
        return task.future;
    }

    private void rejectQueued(String reason) {
        List<Runnable> queued = executor.shutdownNow();
        RejectedExecutionException rejection = new RejectedExecutionException(reason);
        for (Runnable runnable : queued) {
            if (runnable instanceof RecoveryDurabilityService.RequiredTask<?> task) {
                rejected.increment();
                task.reject(rejection);
            }
        }
    }

    private void validateJournalOwnership(Path dataDirectory) {
        Path expectedDirectory = dataDirectory.toAbsolutePath().normalize();
        Path restorationParent = restorationJournal.directory().getParent();
        Path playerParent = playerRecoveryJournal.directory().getParent();
        try {
            if (restorationParent == null
                    || playerParent == null
                    || !Files.isSameFile(restorationParent, expectedDirectory)
                    || !Files.isSameFile(playerParent, expectedDirectory)) {
                throw new IllegalArgumentException(
                        "Recovery journals must belong to the exclusively locked data directory");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Could not verify recovery journal ownership", failure);
        }
    }

    private static Ownership acquireOwnership(Path pluginDataDirectory) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Path dataDirectory = pluginDataDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(dataDirectory)
                || !Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Recovery durability requires a safe existing plugin data directory");
        }
        Path lockPath = dataDirectory.resolve(OWNERSHIP_LOCK_FILE);
        if (Files.isSymbolicLink(lockPath)
                || Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Recovery durability ownership lock is not a safe regular file");
        }

        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException(
                        "Another WalkThePlank recovery durability writer owns this data directory");
            }
            return new Ownership(dataDirectory, channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException(
                    "Could not acquire exclusive recovery durability ownership", failure);
        }
    }

    private static Ownership acquireOwnership(
            Path pluginDataDirectory,
            int queueCapacity,
            RestorationJournal restorationJournal,
            PlayerRecoveryJournal playerRecoveryJournal) {
        Objects.requireNonNull(restorationJournal, "restorationJournal");
        Objects.requireNonNull(playerRecoveryJournal, "playerRecoveryJournal");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        return acquireOwnership(pluginDataDirectory);
    }

    private synchronized boolean releaseOwnership() {
        if (ownershipReleaseAttempted) {
            return ownershipReleaseSucceeded;
        }
        ownershipReleaseAttempted = true;
        ownershipReleaseSucceeded = closeOwnership(
                new Ownership(ownershipDataDirectory, ownershipChannel, ownershipLock));
        return ownershipReleaseSucceeded;
    }

    private static boolean closeOwnership(Ownership ownership) {
        boolean succeeded = true;
        if (ownership.lock().isValid()) {
            try {
                ownership.lock().release();
            } catch (IOException failure) {
                succeeded = false;
            }
        }
        try {
            ownership.channel().close();
        } catch (IOException failure) {
            succeeded = false;
        }
        return succeeded;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class RequiredTask<T> implements Runnable {
        private final Callable<T> operation;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger();

        private RequiredTask(Callable<T> operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(0, 1)) {
                return;
            }
            try {
                future.complete(operation.call());
            } catch (Exception failureValue) {
                failed.increment();
                future.completeExceptionally(failureValue);
            } catch (Error error) {
                failed.increment();
                future.completeExceptionally(error);
                throw error;
            } finally {
                completed.increment();
                state.set(2);
            }
        }

        private void reject(RejectedExecutionException rejection) {
            if (state.compareAndSet(0, 2)) {
                future.completeExceptionally(rejection);
            }
        }
    }

    /** Cancellation-safe, one-shot durable restoration capture. */
    public static final class RestorationPreparation {
        private final RecoveryDurabilityService owner;
        private final RestorationCapture capture;
        private final CompletableFuture<RestorationRecord> durableRecord = new CompletableFuture<>();
        private PreparationState state = PreparationState.PREPARING;
        private RestorationRecord record;
        private CompletableFuture<Void> discardAttempt;

        private RestorationPreparation(
                RecoveryDurabilityService owner,
                RestorationCapture capture) {
            this.owner = owner;
            this.capture = capture;
        }

        public CompletableFuture<RestorationRecord> durableRecord() {
            return durableRecord.copy();
        }

        public UUID sessionId() {
            return capture.sessionId();
        }

        public String arenaId() {
            return capture.arenaId();
        }

        public UUID worldId() {
            return capture.worldId();
        }

        public int x() {
            return capture.x();
        }

        public int y() {
            return capture.y();
        }

        public int z() {
            return capture.z();
        }

        public synchronized boolean claim(RestorationRecord candidate) {
            RestorationRecord checked = Objects.requireNonNull(candidate, "candidate");
            if (state != PreparationState.READY || record == null
                    || !record.equals(checked)) {
                return false;
            }
            state = PreparationState.CLAIMED;
            return true;
        }

        public CompletableFuture<Void> discard() {
            RestorationRecord retainedRecord = null;
            CompletableFuture<Void> attempt = null;
            synchronized (this) {
                switch (state) {
                    case PREPARING -> {
                        state = PreparationState.DISCARD_REQUESTED;
                        discardAttempt = new CompletableFuture<>();
                        return discardAttempt;
                    }
                    case DISCARD_REQUESTED, DISCARDING -> {
                        return Objects.requireNonNull(discardAttempt, "discardAttempt");
                    }
                    case READY, RETAINED -> {
                        state = PreparationState.DISCARDING;
                        retainedRecord = Objects.requireNonNull(record, "record");
                        discardAttempt = new CompletableFuture<>();
                        attempt = discardAttempt;
                    }
                    case CLAIMED -> {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "A claimed restoration must be restored, not discarded"));
                    }
                    case DISCARDED, FAILED_CLEAN -> {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            }
            RestorationRecord checkedRecord =
                    Objects.requireNonNull(retainedRecord, "retainedRecord");
            CompletableFuture<Void> checkedAttempt =
                    Objects.requireNonNull(attempt, "attempt");
            owner.completeRestoration(checkedRecord.journalId())
                    .whenComplete((ignored, failure) ->
                            completeDiscard(checkedAttempt, failure));
            return checkedAttempt;
        }

        private void persist() throws IOException {
            RestorationRecord persisted;
            try {
                persisted = owner.restorationJournal.append(
                        capture.sessionId(),
                        capture.arenaId(),
                        capture.worldId(),
                        capture.worldName(),
                        capture.x(),
                        capture.y(),
                        capture.z(),
                        capture.expectedState(),
                        capture.originalState(),
                        capture.originalStructure());
            } catch (RestorationJournalCommitUncertainException failure) {
                handleUncertainCommit(failure.record(), failure);
                throw failure;
            } catch (IOException | RuntimeException failure) {
                failClean(failure);
                throw failure;
            }

            boolean discardNow;
            CompletableFuture<Void> attempt = null;
            synchronized (this) {
                record = persisted;
                discardNow = state == PreparationState.DISCARD_REQUESTED;
                if (discardNow) {
                    state = PreparationState.DISCARDING;
                    attempt = Objects.requireNonNull(discardAttempt, "discardAttempt");
                } else if (state == PreparationState.PREPARING) {
                    state = PreparationState.READY;
                } else {
                    IllegalStateException failure =
                            new IllegalStateException("Invalid restoration preparation state " + state);
                    failCleanLocked(failure);
                    throw failure;
                }
            }
            if (discardNow) {
                try {
                    owner.restorationJournal.complete(persisted.journalId());
                    completeDiscard(attempt, null);
                } catch (IOException | RuntimeException failure) {
                    completeDiscard(attempt, failure);
                    throw failure;
                }
                return;
            }
            durableRecord.complete(persisted);
        }

        private synchronized void submissionFailed(Throwable failure) {
            if (!durableRecord.isDone()) {
                if (state == PreparationState.PREPARING
                        || state == PreparationState.DISCARD_REQUESTED) {
                    failCleanLocked(failure);
                } else {
                    durableRecord.completeExceptionally(failure);
                }
            }
        }

        private void handleUncertainCommit(
                RestorationRecord retainedRecord,
                Throwable failure) {
            CompletableFuture<Void> attempt = null;
            boolean discardNow;
            synchronized (this) {
                record = Objects.requireNonNull(retainedRecord, "retainedRecord");
                durableRecord.completeExceptionally(failure);
                discardNow = state == PreparationState.DISCARD_REQUESTED;
                if (discardNow) {
                    state = PreparationState.DISCARDING;
                    attempt = Objects.requireNonNull(discardAttempt, "discardAttempt");
                } else if (state == PreparationState.PREPARING) {
                    state = PreparationState.RETAINED;
                } else {
                    state = PreparationState.RETAINED;
                    if (discardAttempt != null) {
                        discardAttempt.completeExceptionally(failure);
                        discardAttempt = null;
                    }
                    return;
                }
            }
            if (discardNow) {
                try {
                    owner.restorationJournal.complete(retainedRecord.journalId());
                    completeDiscard(attempt, null);
                } catch (IOException | RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    completeDiscard(attempt, cleanupFailure);
                }
            }
        }

        private synchronized void failClean(Throwable failure) {
            failCleanLocked(failure);
        }

        private void failCleanLocked(Throwable failure) {
            state = PreparationState.FAILED_CLEAN;
            durableRecord.completeExceptionally(failure);
            if (discardAttempt != null) {
                discardAttempt.complete(null);
                discardAttempt = null;
            }
        }

        private void completeDiscard(
                CompletableFuture<Void> attempt,
                Throwable failure) {
            Throwable unwrapped = failure == null ? null : unwrap(failure);
            synchronized (this) {
                if (discardAttempt != attempt || state != PreparationState.DISCARDING) {
                    return;
                }
                discardAttempt = null;
                state = unwrapped == null
                        ? PreparationState.DISCARDED
                        : PreparationState.RETAINED;
            }
            if (unwrapped == null) {
                durableRecord.completeExceptionally(
                        new CancellationException("Restoration preparation was discarded"));
                attempt.complete(null);
            } else {
                attempt.completeExceptionally(unwrapped);
            }
        }
    }

    /** Cancellation-safe player-state record prepared before any temporary player mutation. */
    public static final class PlayerRecoveryPreparation {
        private final RecoveryDurabilityService owner;
        private final PlayerRecoveryRecord captured;
        private final CompletableFuture<PlayerRecoveryRecord> durableRecord = new CompletableFuture<>();
        private PreparationState state = PreparationState.PREPARING;
        private PlayerRecoveryRecord record;
        private CompletableFuture<Void> discardAttempt;

        private PlayerRecoveryPreparation(
                RecoveryDurabilityService owner,
                PlayerRecoveryRecord captured) {
            this.owner = owner;
            this.captured = captured;
        }

        public CompletableFuture<PlayerRecoveryRecord> durableRecord() {
            return durableRecord.copy();
        }

        public synchronized boolean claim(PlayerRecoveryRecord candidate) {
            PlayerRecoveryRecord checked = Objects.requireNonNull(candidate, "candidate");
            if (state != PreparationState.READY || record == null || !record.equals(checked)) {
                return false;
            }
            state = PreparationState.CLAIMED;
            return true;
        }

        public CompletableFuture<Void> discard() {
            PlayerRecoveryRecord retainedRecord = null;
            CompletableFuture<Void> attempt = null;
            synchronized (this) {
                switch (state) {
                    case PREPARING -> {
                        state = PreparationState.DISCARD_REQUESTED;
                        discardAttempt = new CompletableFuture<>();
                        return discardAttempt;
                    }
                    case DISCARD_REQUESTED, DISCARDING -> {
                        return Objects.requireNonNull(discardAttempt, "discardAttempt");
                    }
                    case READY, RETAINED -> {
                        state = PreparationState.DISCARDING;
                        retainedRecord = Objects.requireNonNull(record, "record");
                        discardAttempt = new CompletableFuture<>();
                        attempt = discardAttempt;
                    }
                    case CLAIMED -> {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Claimed player recovery evidence must remain until recovery completes"));
                    }
                    case DISCARDED, FAILED_CLEAN -> {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            }
            PlayerRecoveryRecord checkedRecord =
                    Objects.requireNonNull(retainedRecord, "retainedRecord");
            CompletableFuture<Void> checkedAttempt =
                    Objects.requireNonNull(attempt, "attempt");
            owner.completePlayerRecovery(
                            checkedRecord.playerId(),
                            checkedRecord.runId(),
                            checkedRecord.arenaId())
                    .whenComplete((ignored, failure) ->
                            completeDiscard(checkedAttempt, failure));
            return checkedAttempt;
        }

        private void persist() throws IOException {
            PlayerRecoveryRecord persisted;
            try {
                persisted = owner.playerRecoveryJournal.append(captured);
            } catch (PlayerRecoveryJournalCommitUncertainException failure) {
                handleUncertainCommit(failure.record(), failure);
                throw failure;
            } catch (IOException | RuntimeException failure) {
                failClean(failure);
                throw failure;
            }
            boolean discardNow;
            CompletableFuture<Void> attempt = null;
            synchronized (this) {
                record = persisted;
                discardNow = state == PreparationState.DISCARD_REQUESTED;
                if (discardNow) {
                    state = PreparationState.DISCARDING;
                    attempt = Objects.requireNonNull(discardAttempt, "discardAttempt");
                } else if (state == PreparationState.PREPARING) {
                    state = PreparationState.READY;
                } else {
                    IllegalStateException failure =
                            new IllegalStateException("Invalid player recovery preparation state " + state);
                    failCleanLocked(failure);
                    throw failure;
                }
            }
            if (discardNow) {
                try {
                    owner.playerRecoveryJournal.complete(
                            persisted.playerId(),
                            persisted.runId(),
                            persisted.arenaId());
                    completeDiscard(attempt, null);
                } catch (IOException | RuntimeException failure) {
                    completeDiscard(attempt, failure);
                    throw failure;
                }
                return;
            }
            durableRecord.complete(persisted);
        }

        private synchronized void submissionFailed(Throwable failure) {
            if (!durableRecord.isDone()) {
                if (state == PreparationState.PREPARING
                        || state == PreparationState.DISCARD_REQUESTED) {
                    failCleanLocked(failure);
                } else {
                    durableRecord.completeExceptionally(failure);
                }
            }
        }

        private void handleUncertainCommit(
                PlayerRecoveryRecord retainedRecord,
                Throwable failure) {
            CompletableFuture<Void> attempt = null;
            boolean discardNow;
            synchronized (this) {
                record = Objects.requireNonNull(retainedRecord, "retainedRecord");
                durableRecord.completeExceptionally(failure);
                discardNow = state == PreparationState.DISCARD_REQUESTED;
                if (discardNow) {
                    state = PreparationState.DISCARDING;
                    attempt = Objects.requireNonNull(discardAttempt, "discardAttempt");
                } else if (state == PreparationState.PREPARING) {
                    state = PreparationState.RETAINED;
                } else {
                    state = PreparationState.RETAINED;
                    if (discardAttempt != null) {
                        discardAttempt.completeExceptionally(failure);
                        discardAttempt = null;
                    }
                    return;
                }
            }
            if (discardNow) {
                try {
                    owner.playerRecoveryJournal.complete(
                            retainedRecord.playerId(),
                            retainedRecord.runId(),
                            retainedRecord.arenaId());
                    completeDiscard(attempt, null);
                } catch (IOException | RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    completeDiscard(attempt, cleanupFailure);
                }
            }
        }

        private synchronized void failClean(Throwable failure) {
            failCleanLocked(failure);
        }

        private void failCleanLocked(Throwable failure) {
            state = PreparationState.FAILED_CLEAN;
            durableRecord.completeExceptionally(failure);
            if (discardAttempt != null) {
                discardAttempt.complete(null);
                discardAttempt = null;
            }
        }

        private void completeDiscard(
                CompletableFuture<Void> attempt,
                Throwable failure) {
            Throwable unwrapped = failure == null ? null : unwrap(failure);
            synchronized (this) {
                if (discardAttempt != attempt || state != PreparationState.DISCARDING) {
                    return;
                }
                discardAttempt = null;
                state = unwrapped == null
                        ? PreparationState.DISCARDED
                        : PreparationState.RETAINED;
            }
            if (unwrapped == null) {
                durableRecord.completeExceptionally(
                        new CancellationException("Player recovery preparation was discarded"));
                attempt.complete(null);
            } else {
                attempt.completeExceptionally(unwrapped);
            }
        }
    }

    private enum PreparationState {
        PREPARING,
        READY,
        CLAIMED,
        DISCARD_REQUESTED,
        DISCARDING,
        RETAINED,
        DISCARDED,
        FAILED_CLEAN
    }

    private record RestorationCapture(
            UUID sessionId,
            String arenaId,
            UUID worldId,
            String worldName,
            int x,
            int y,
            int z,
            SerializedBlockState expectedState,
            SerializedBlockState originalState,
            byte[] originalStructure) {
        private RestorationCapture {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(arenaId, "arenaId");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(originalState, "originalState");
            originalStructure = Objects.requireNonNull(
                    originalStructure, "originalStructure").clone();
        }

        @Override
        public byte[] originalStructure() {
            return originalStructure.clone();
        }
    }

    private record Ownership(Path dataDirectory, FileChannel channel, FileLock lock) {
        private Ownership {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(lock, "lock");
        }
    }

    /** Privacy-safe queue/lifecycle counters exposed by the doctor report. */
    public record Status(
            int queueCapacity,
            int queued,
            int active,
            long accepted,
            long completed,
            long failed,
            long rejected,
            boolean closing,
            boolean terminated) {
        public int remainingCapacity() {
            return Math.max(0, queueCapacity - queued);
        }
    }
}
