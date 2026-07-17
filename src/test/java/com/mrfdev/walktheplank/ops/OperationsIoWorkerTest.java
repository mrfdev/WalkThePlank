package com.mrfdev.walktheplank.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationsIoWorkerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void executesAcceptedRequiredWorkInFifoOrderOnNamedSingleWriter() throws Exception {
        OperationsIoWorker worker = new OperationsIoWorker(8);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<String> first = worker.submitRequired(() -> {
            order.add(1);
            firstStarted.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            return Thread.currentThread().getName();
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        CompletableFuture<String> second = worker.submitRequired(() -> {
            order.add(2);
            return Thread.currentThread().getName();
        });
        CompletableFuture<String> third = worker.submitRequired(() -> {
            order.add(3);
            return Thread.currentThread().getName();
        });

        releaseFirst.countDown();
        assertEquals(OperationsIoWorker.THREAD_NAME, first.get(2, TimeUnit.SECONDS));
        assertEquals(OperationsIoWorker.THREAD_NAME, second.get(2, TimeUnit.SECONDS));
        assertEquals(OperationsIoWorker.THREAD_NAME, third.get(2, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2, 3), order);
        assertTrue(worker.close(Duration.ofSeconds(2)));

        OperationsIoWorker.Status status = worker.status();
        assertEquals(3, status.requiredAccepted());
        assertEquals(3, status.completed());
        assertEquals(0, status.failed());
        assertEquals(0, status.rejected());
        assertTrue(status.terminated());
    }

    @Test
    void saturationRejectsWithoutRunningAnythingOnTheCaller() throws Exception {
        OperationsIoWorker worker = new OperationsIoWorker(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean rejectedRequiredRan = new AtomicBoolean();
        AtomicBoolean rejectedBestEffortRan = new AtomicBoolean();

        CompletableFuture<Void> running = worker.submitRequired(() -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            return null;
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        CompletableFuture<Integer> queued = worker.submitRequired(() -> 2);

        CompletableFuture<Integer> rejected = worker.submitRequired(() -> {
            rejectedRequiredRan.set(true);
            return 3;
        });
        CompletionException rejection = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(RejectedExecutionException.class, rejection.getCause());
        assertFalse(worker.submitBestEffort(() -> rejectedBestEffortRan.set(true)));
        assertFalse(rejectedRequiredRan.get());
        assertFalse(rejectedBestEffortRan.get());

        releaseFirst.countDown();
        running.get(2, TimeUnit.SECONDS);
        assertEquals(2, queued.get(2, TimeUnit.SECONDS));
        assertTrue(worker.close(Duration.ofSeconds(2)));
        assertEquals(2, worker.status().rejected());
    }

    @Test
    void requiredFailuresAreReportedAndClosedWorkersRejectNewWork() throws Exception {
        OperationsIoWorker worker = new OperationsIoWorker(4);

        CompletableFuture<Void> failed = worker.submitRequired(() -> {
            throw new IOException("expected test failure");
        });
        CompletionException operationFailure = assertThrows(CompletionException.class, failed::join);
        assertInstanceOf(IOException.class, operationFailure.getCause());
        assertEquals("still-running", worker.submitRequired(() -> "still-running")
                .get(2, TimeUnit.SECONDS));
        assertTrue(worker.close(Duration.ofSeconds(2)));

        CompletableFuture<Void> rejected = worker.submitRequired(() -> null);
        CompletionException closedFailure = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(RejectedExecutionException.class, closedFailure.getCause());
        assertFalse(worker.submitBestEffort(() -> {
            throw new AssertionError("closed worker ran rejected best-effort work");
        }));

        OperationsIoWorker.Status status = worker.status();
        assertEquals(1, status.failed());
        assertEquals(2, status.completed());
        assertEquals(2, status.rejected());
        assertTrue(status.closing());
        assertTrue(status.terminated());
    }

    @Test
    void closeTimeoutInterruptsRunningWorkAndRejectsQueuedRequiredWork() throws Exception {
        OperationsIoWorker worker = new OperationsIoWorker(2);
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        CompletableFuture<Void> running = worker.submitRequired(() -> {
            runningStarted.countDown();
            neverReleased.await();
            return null;
        });
        assertTrue(runningStarted.await(2, TimeUnit.SECONDS));
        CompletableFuture<Integer> queued = worker.submitRequired(() -> 2);

        worker.close(Duration.ZERO);

        CompletionException queuedFailure = assertThrows(CompletionException.class, queued::join);
        assertInstanceOf(RejectedExecutionException.class, queuedFailure.getCause());
        CompletionException runningFailure = assertThrows(CompletionException.class, running::join);
        assertInstanceOf(InterruptedException.class, runningFailure.getCause());
        assertTrue(worker.close(Duration.ofSeconds(2)));
        assertTrue(worker.status().terminated());
    }

    @Test
    void exclusiveOwnershipBlocksASecondRuntimeUntilTheWriterTerminates() {
        OperationsIoWorker first =
                new OperationsIoWorker(temporaryDirectory, 2);

        assertThrows(
                IllegalStateException.class,
                () -> new OperationsIoWorker(temporaryDirectory, 2));

        assertTrue(first.close(Duration.ofSeconds(2)));
        OperationsIoWorker replacement =
                new OperationsIoWorker(temporaryDirectory, 2);
        assertTrue(replacement.close(Duration.ofSeconds(2)));
    }

    @Test
    void ownershipReleasesAfterTimedOutCloseWhenWriterEventuallyTerminates() throws Exception {
        OperationsIoWorker first =
                new OperationsIoWorker(temporaryDirectory, 2);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        CompletableFuture<Void> running = first.submitRequired(() -> {
            started.countDown();
            while (!release.get()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    // Deliberately emulate an I/O provider that does not honor interruption.
                }
            }
            return null;
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));

        assertFalse(first.close(Duration.ofMillis(25)));
        assertThrows(
                IllegalStateException.class,
                () -> new OperationsIoWorker(temporaryDirectory, 2));

        release.set(true);
        running.get(2, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!first.status().terminated() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(first.status().terminated());

        OperationsIoWorker replacement =
                new OperationsIoWorker(temporaryDirectory, 2);
        assertTrue(replacement.close(Duration.ofSeconds(2)));
    }
}
