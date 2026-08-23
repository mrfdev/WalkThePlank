package com.mrfdev.walktheplank.ops;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, plugin-owned single writer for non-gameplay filesystem work.
 *
 * <p>Submission never runs work on the calling thread. Required work receives a failed future
 * when the bounded queue is full or closing; best-effort work reports rejection with
 * {@code false}. This worker is deliberately independent from recovery durability workers.</p>
 */
public final class OperationsIoWorker {
    public static final int DEFAULT_QUEUE_CAPACITY = 1_024;
    public static final String THREAD_NAME = "walktheplank-operations-writer";
    private static final String OWNERSHIP_LOCK_FILE = ".operations-io.lock";

    private final int queueCapacity;
    private final ThreadPoolExecutor executor;
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private final LongAdder requiredAccepted = new LongAdder();
    private final LongAdder bestEffortAccepted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private volatile Thread workerThread;
    private boolean ownershipReleaseAttempted;
    private boolean ownershipReleaseSucceeded;

    public OperationsIoWorker() {
        this(null, DEFAULT_QUEUE_CAPACITY);
    }

    public OperationsIoWorker(int queueCapacity) {
        this(null, queueCapacity);
    }

    public OperationsIoWorker(Path pluginDataDirectory) {
        this(pluginDataDirectory, DEFAULT_QUEUE_CAPACITY);
    }

    OperationsIoWorker(Path pluginDataDirectory, int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        this.queueCapacity = queueCapacity;
        Ownership ownership = pluginDataDirectory == null
                ? null
                : acquireOwnership(pluginDataDirectory);
        ownershipChannel = ownership == null ? null : ownership.channel();
        ownershipLock = ownership == null ? null : ownership.lock();
        try {
            this.executor = new ThreadPoolExecutor(
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
            closeOwnership(ownership);
            throw failure;
        }
    }

    /**
     * Accepts required work without blocking the caller.
     *
     * <p>The returned future fails with {@link RejectedExecutionException} if the bounded queue is
     * full or the worker has started closing.</p>
     */
    public <T> CompletableFuture<T> submitRequired(Callable<T> operation) {
        RequiredTask<T> task = new RequiredTask<>(Objects.requireNonNull(operation, "operation"));
        if (submit(task)) {
            requiredAccepted.increment();
        }
        return task.future();
    }

    /**
     * Attempts to enqueue best-effort work without blocking or falling back to the calling thread.
     *
     * @return {@code true} only when the work was accepted
     */
    public boolean submitBestEffort(Runnable operation) {
        BestEffortTask task = new BestEffortTask(Objects.requireNonNull(operation, "operation"));
        if (!submit(task)) {
            return false;
        }
        bestEffortAccepted.increment();
        return true;
    }

    public Status status() {
        return new Status(
                queueCapacity,
                executor.getQueue().size(),
                executor.getActiveCount(),
                requiredAccepted.sum(),
                bestEffortAccepted.sum(),
                completed.sum(),
                failed.sum(),
                rejected.sum(),
                executor.isShutdown(),
                executor.isTerminated());
    }

    boolean ownsCurrentThread() {
        return Thread.currentThread() == workerThread;
    }

    /**
     * Stops acceptance and waits for accepted work to drain.
     *
     * <p>If the timeout expires, queued work is rejected and running work is interrupted. Required
     * work removed from the queue completes exceptionally. Calling this method from the writer
     * initiates shutdown but returns {@code false} rather than waiting on itself.</p>
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

        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            timeoutNanos = Long.MAX_VALUE;
        }

        try {
            if (!executor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS)) {
                rejectQueued("Operations worker close timed out");
                if (!executor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS)) {
                    return false;
                }
            }
            return releaseOwnership();
        } catch (InterruptedException exception) {
            rejectQueued("Operations worker close was interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Ownership acquireOwnership(Path pluginDataDirectory) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Path dataDirectory = pluginDataDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(dataDirectory)
                || !Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Operations I/O requires a safe existing plugin data directory");
        }
        Path lockPath = dataDirectory.resolve(OWNERSHIP_LOCK_FILE);
        if (Files.isSymbolicLink(lockPath)
                || Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Operations I/O ownership lock is not a safe regular file");
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
                        "Another WalkThePlank operations writer owns this data directory");
            }
            return new Ownership(channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException(
                    "Could not acquire exclusive operations I/O ownership", failure);
        }
    }

    private synchronized boolean releaseOwnership() {
        if (ownershipReleaseAttempted) {
            return ownershipReleaseSucceeded;
        }
        ownershipReleaseAttempted = true;
        ownershipReleaseSucceeded = closeOwnership(
                ownershipChannel == null || ownershipLock == null
                        ? null
                        : new Ownership(ownershipChannel, ownershipLock));
        return ownershipReleaseSucceeded;
    }

    private static boolean closeOwnership(Ownership ownership) {
        if (ownership == null) {
            return true;
        }
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

    private boolean submit(WorkerTask task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            rejected.increment();
            task.reject(exception);
            return false;
        }
    }

    private void rejectQueued(String reason) {
        List<Runnable> queued = executor.shutdownNow();
        RejectedExecutionException rejection = new RejectedExecutionException(reason);
        for (Runnable runnable : queued) {
            if (runnable instanceof WorkerTask task) {
                rejected.increment();
                task.reject(rejection);
            }
        }
    }

    private interface WorkerTask extends Runnable {
        void reject(RejectedExecutionException failure);
    }

    private record Ownership(FileChannel channel, FileLock lock) {
        private Ownership {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(lock, "lock");
        }
    }

    private final class RequiredTask<T> implements WorkerTask {
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
            } catch (Exception failure) {
                failed.increment();
                future.completeExceptionally(failure);
            } catch (Error error) {
                failed.increment();
                future.completeExceptionally(error);
                throw error;
            } finally {
                completed.increment();
                state.set(2);
            }
        }

        @Override
        public void reject(RejectedExecutionException failure) {
            if (state.compareAndSet(0, 2)) {
                future.completeExceptionally(failure);
            }
        }

        private CompletableFuture<T> future() {
            return future;
        }
    }

    private final class BestEffortTask implements WorkerTask {
        private final Runnable operation;
        private final AtomicBoolean started = new AtomicBoolean();

        private BestEffortTask(Runnable operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                operation.run();
            } catch (RuntimeException failure) {
                failed.increment();
            } catch (Error error) {
                failed.increment();
                throw error;
            } finally {
                completed.increment();
            }
        }

        @Override
        public void reject(RejectedExecutionException failure) {
            started.compareAndSet(false, true);
        }
    }

    /** Privacy-safe process-local queue and lifecycle counters. */
    public record Status(
            int queueCapacity,
            int queued,
            int active,
            long requiredAccepted,
            long bestEffortAccepted,
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
