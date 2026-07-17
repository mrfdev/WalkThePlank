package com.mrfdev.walktheplank.recovery;

import com.mrfdev.walktheplank.ops.OperationsIoWorker;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryDurabilityServiceTest {
    private static final UUID SESSION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final byte[] STRUCTURE = {10, 0, 3, 1, 2, 3, 4, 5};
    private static final SerializedBlockState EXPECTED =
            new SerializedBlockState("EMERALD_BLOCK", "minecraft:emerald_block");
    private static final SerializedBlockState ORIGINAL =
            new SerializedBlockState("AIR", "minecraft:air");

    @TempDir
    Path temporaryDirectory;

    @Test
    void restorationBytesAreDefensivelyCapturedAndPersistedOnDedicatedWriter() throws Exception {
        RestorationJournal restoration = restorationJournal();
        PlayerRecoveryJournal players = playerJournal();
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(restoration, players, temporaryDirectory, 4);
        byte[] mutableStructure = STRUCTURE.clone();

        RecoveryDurabilityService.RestorationPreparation preparation;
        CompletableFuture<String> completionThread;
        synchronized (restoration) {
            preparation = prepare(service, mutableStructure, 10);
            mutableStructure[0] = 99;
            completionThread = preparation.durableRecord()
                    .thenApply(ignored -> Thread.currentThread().getName());
            assertFalse(preparation.durableRecord().isDone());
        }

        RestorationRecord record = preparation.durableRecord().get(5, TimeUnit.SECONDS);
        assertArrayEquals(STRUCTURE, record.originalStructure());
        assertEquals(RecoveryDurabilityService.THREAD_NAME, completionThread.get(5, TimeUnit.SECONDS));
        assertEquals(1, restoration.pendingCount());
        service.completeRestoration(record.journalId()).get(5, TimeUnit.SECONDS);
        assertTrue(service.flush(Duration.ofSeconds(5)));
        assertEquals(0, restoration.pendingCount());
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    @Test
    void discardBeforeAppendFinishesLeavesNoOrphanedJournalRecord() throws Exception {
        RestorationJournal restoration = restorationJournal();
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 4);

        RecoveryDurabilityService.RestorationPreparation preparation;
        CompletableFuture<Void> discarded;
        synchronized (restoration) {
            preparation = prepare(service, STRUCTURE, 20);
            discarded = preparation.discard();
            assertFalse(discarded.isDone());
        }

        discarded.get(5, TimeUnit.SECONDS);
        java.util.concurrent.CompletionException cancelled = assertThrows(
                java.util.concurrent.CompletionException.class,
                preparation.durableRecord()::join);
        assertInstanceOf(
                java.util.concurrent.CancellationException.class,
                cancelled.getCause());
        assertEquals(0, restoration.pendingCount());
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    @Test
    void restorationDiscardBeforeAppendRetainsAndRetriesAfterDeleteDirectoryFsyncFailure()
            throws Exception {
        AtomicInteger directoryForces = new AtomicInteger();
        RestorationJournal restoration = RestorationJournal.open(
                temporaryDirectory.toRealPath(),
                "test-release",
                UUID::randomUUID,
                System::currentTimeMillis,
                ignored -> {
                    if (directoryForces.incrementAndGet() == 2) {
                        throw new IOException("simulated post-delete directory fsync failure");
                    }
                });
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 4);

        RecoveryDurabilityService.RestorationPreparation preparation;
        CompletableFuture<Void> firstDiscard;
        synchronized (restoration) {
            preparation = prepare(service, STRUCTURE, 21);
            firstDiscard = preparation.discard();
        }

        ExecutionException cleanupFailure = assertThrows(
                ExecutionException.class,
                () -> firstDiscard.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, cleanupFailure.getCause());
        assertThrows(
                ExecutionException.class,
                () -> preparation.durableRecord().get(5, TimeUnit.SECONDS));
        assertEquals(1, restoration.pendingCount());
        assertTrue(restoration.pendingFileBytes() > 0L);

        preparation.discard().get(5, TimeUnit.SECONDS);
        assertEquals(0, restoration.pendingCount());
        assertEquals(0L, restoration.pendingFileBytes());
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    @Test
    void playerDiscardBeforeAppendRetainsAndRetriesAfterDeleteDirectoryFsyncFailure()
            throws Exception {
        AtomicInteger directoryForces = new AtomicInteger();
        PlayerRecoveryJournal players = PlayerRecoveryJournal.open(
                temporaryDirectory.toRealPath(),
                ignored -> {
                    if (directoryForces.incrementAndGet() == 2) {
                        throw new IOException("simulated post-delete directory fsync failure");
                    }
                });
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restorationJournal(), players, temporaryDirectory, 4);
        PlayerRecoveryRecord captured = playerRecord(
                UUID.fromString("40000000-0000-0000-0000-000000000003"));

        RecoveryDurabilityService.PlayerRecoveryPreparation preparation;
        CompletableFuture<Void> firstDiscard;
        synchronized (players) {
            preparation = service.preparePlayerRecovery(captured);
            firstDiscard = preparation.discard();
        }

        ExecutionException cleanupFailure = assertThrows(
                ExecutionException.class,
                () -> firstDiscard.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, cleanupFailure.getCause());
        assertThrows(
                ExecutionException.class,
                () -> preparation.durableRecord().get(5, TimeUnit.SECONDS));
        assertEquals(captured, players.pending(PLAYER_ID).orElseThrow());

        preparation.discard().get(5, TimeUnit.SECONDS);
        assertTrue(players.pending(PLAYER_ID).isEmpty());
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    @Test
    void saturatedQueueRejectsInsteadOfRunningDurabilityOnCaller() throws Exception {
        RestorationJournal restoration = restorationJournal();
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 1);

        RecoveryDurabilityService.RestorationPreparation first;
        RecoveryDurabilityService.RestorationPreparation second;
        RecoveryDurabilityService.RestorationPreparation rejected;
        synchronized (restoration) {
            first = prepare(service, STRUCTURE, 30);
            awaitActiveWriter(service);
            second = prepare(service, STRUCTURE, 31);
            assertEquals(1, service.status().queued());
            rejected = prepare(service, STRUCTURE, 32);
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> rejected.durableRecord().get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            assertEquals(1L, service.status().rejected());
            assertEquals(0, restoration.pendingCount());
        }

        RestorationRecord firstRecord = first.durableRecord().get(5, TimeUnit.SECONDS);
        RestorationRecord secondRecord = second.durableRecord().get(5, TimeUnit.SECONDS);
        service.completeRestoration(firstRecord.journalId()).get(5, TimeUnit.SECONDS);
        service.completeRestoration(secondRecord.journalId()).get(5, TimeUnit.SECONDS);
        assertTrue(service.close(Duration.ofSeconds(5)));
        assertEquals(0, restoration.pendingCount());
    }

    @Test
    void playerRecoveryUsesSameFifoBarrierAndExactCompletionOwnership() throws Exception {
        RestorationJournal restoration = restorationJournal();
        PlayerRecoveryJournal players = playerJournal();
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(restoration, players, temporaryDirectory, 4);
        UUID runId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        PlayerRecoveryRecord captured = new PlayerRecoveryRecord(
                PLAYER_ID,
                runId,
                "main",
                WORLD_ID,
                12.25,
                73.0,
                -9.75,
                45.0F,
                -12.0F,
                17.5,
                16,
                3.5F,
                0.25F,
                0.35F,
                true,
                true,
                false);

        RecoveryDurabilityService.PlayerRecoveryPreparation preparation =
                service.preparePlayerRecovery(captured);
        PlayerRecoveryRecord persisted =
                preparation.durableRecord().get(5, TimeUnit.SECONDS);
        assertEquals(captured, persisted);
        assertTrue(preparation.claim(persisted));
        assertEquals(captured, players.pending(PLAYER_ID).orElseThrow());

        service.completePlayerRecovery(PLAYER_ID, runId, "main").get(5, TimeUnit.SECONDS);
        assertTrue(service.flush(Duration.ofSeconds(5)));
        assertTrue(players.pending(PLAYER_ID).isEmpty());
        assertTrue(service.close(Duration.ofSeconds(5)));
        assertTrue(service.status().terminated());
    }

    @Test
    void blockedRecoveryJournalCannotDelayIndependentOperationsWriter() throws Exception {
        RestorationJournal restoration = restorationJournal();
        RecoveryDurabilityService recovery =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 2);
        OperationsIoWorker operations = new OperationsIoWorker(2);

        RecoveryDurabilityService.RestorationPreparation preparation;
        synchronized (restoration) {
            preparation = prepare(recovery, STRUCTURE, 40);
            awaitActiveWriter(recovery);
            assertEquals(
                    OperationsIoWorker.THREAD_NAME,
                    operations.submitRequired(() -> Thread.currentThread().getName())
                            .get(2, TimeUnit.SECONDS));
            assertFalse(preparation.durableRecord().isDone());
        }

        RestorationRecord record = preparation.durableRecord().get(5, TimeUnit.SECONDS);
        recovery.completeRestoration(record.journalId()).get(5, TimeUnit.SECONDS);
        assertTrue(recovery.close(Duration.ofSeconds(5)));
        assertTrue(operations.close(Duration.ofSeconds(5)));
    }

    @Test
    void exclusiveOwnershipPreventsOverlappingWriterInstances() throws Exception {
        RestorationJournal restoration = restorationJournal();
        PlayerRecoveryJournal players = playerJournal();
        RecoveryDurabilityService first =
                new RecoveryDurabilityService(
                        restoration, players, temporaryDirectory, 2);

        assertThrows(
                IllegalStateException.class,
                () -> new RecoveryDurabilityService(
                        restoration, players, temporaryDirectory, 2));

        assertTrue(first.close(Duration.ofSeconds(5)));
        RecoveryDurabilityService replacement =
                new RecoveryDurabilityService(
                        restoration, players, temporaryDirectory, 2);
        assertTrue(replacement.close(Duration.ofSeconds(5)));
    }

    @Test
    void fullOpenAcquiresOwnershipBeforeJournalTemporaryFileCleanup() throws Exception {
        RecoveryDurabilityService first = RecoveryDurabilityService.open(
                temporaryDirectory.toRealPath(), "test-release", 2);
        Path liveTemporary = temporaryDirectory
                .resolve("restoration-journal")
                .resolve(SESSION_ID + ".writer-owned.tmp");
        Files.writeString(liveTemporary, "writer-owned");

        assertThrows(
                IllegalStateException.class,
                () -> RecoveryDurabilityService.open(
                        temporaryDirectory.toRealPath(), "test-release", 2));
        assertTrue(Files.exists(liveTemporary));

        Files.delete(liveTemporary);
        assertTrue(first.close(Duration.ofSeconds(5)));
        RecoveryDurabilityService replacement = RecoveryDurabilityService.open(
                temporaryDirectory.toRealPath(), "test-release", 2);
        assertTrue(replacement.close(Duration.ofSeconds(5)));
    }

    @Test
    void ownershipReleasesWhenTimedOutWriterTerminatesAfterCloseReturns() throws Exception {
        CountDownLatch forceEntered = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        RestorationJournal restoration = RestorationJournal.open(
                temporaryDirectory.toRealPath(),
                "test-release",
                UUID::randomUUID,
                System::currentTimeMillis,
                ignored -> {
                    forceEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            releaseForce.await();
                            released = true;
                        } catch (InterruptedException ignoredInterrupt) {
                            // Emulate a storage provider that does not honor interruption.
                        }
                    }
                });
        RecoveryDurabilityService first = new RecoveryDurabilityService(
                restoration, playerJournal(), temporaryDirectory, 2);
        RecoveryDurabilityService.RestorationPreparation preparation =
                prepare(first, STRUCTURE, 51);
        assertTrue(forceEntered.await(2, TimeUnit.SECONDS));

        assertFalse(first.close(Duration.ofMillis(25)));
        assertThrows(
                IllegalStateException.class,
                () -> RecoveryDurabilityService.open(
                        temporaryDirectory.toRealPath(), "test-release", 2));
        releaseForce.countDown();

        RestorationRecord retained =
                preparation.durableRecord().get(5, TimeUnit.SECONDS);
        CountDownLatch terminated = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!first.status().terminated() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            if (first.status().terminated()) {
                terminated.countDown();
            }
        });
        assertTrue(terminated.await(5, TimeUnit.SECONDS));

        RecoveryDurabilityService replacement = RecoveryDurabilityService.open(
                temporaryDirectory.toRealPath(), "test-release", 2);
        replacement.completeRestoration(retained.journalId()).get(5, TimeUnit.SECONDS);
        assertTrue(replacement.close(Duration.ofSeconds(5)));
    }

    @Test
    void closeWaitsAgainAfterInterruptingAnInFlightWriter() throws Exception {
        RestorationJournal restoration = restorationJournal();
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 2);
        CompletableFuture<Boolean> closing;

        synchronized (restoration) {
            prepare(service, STRUCTURE, 50);
            awaitActiveWriter(service);
            closing = CompletableFuture.supplyAsync(
                    () -> service.close(Duration.ofMillis(250)));
            Thread.sleep(300L);
            assertFalse(closing.isDone());
        }

        assertTrue(closing.get(2, TimeUnit.SECONDS));
        assertTrue(service.status().terminated());
    }

    @Test
    void rejectedReadyDiscardCanBeRetriedWithoutClaimingRetainedEvidence() throws Exception {
        RestorationJournal restoration = restorationJournal();
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 1);
        RecoveryDurabilityService.RestorationPreparation retained =
                prepare(service, STRUCTURE, 60);
        RestorationRecord retainedRecord =
                retained.durableRecord().get(5, TimeUnit.SECONDS);
        RecoveryDurabilityService.RestorationPreparation blocked;
        RecoveryDurabilityService.RestorationPreparation queued;

        synchronized (restoration) {
            blocked = prepare(service, STRUCTURE, 61);
            awaitActiveWriter(service);
            queued = prepare(service, STRUCTURE, 62);
            ExecutionException rejection = assertThrows(
                    ExecutionException.class,
                    () -> retained.discard().get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, rejection.getCause());
            assertTrue(restoration.isPending(retainedRecord.journalId()));
            assertFalse(retained.claim(retainedRecord));
        }

        RestorationRecord blockedRecord =
                blocked.durableRecord().get(5, TimeUnit.SECONDS);
        RestorationRecord queuedRecord =
                queued.durableRecord().get(5, TimeUnit.SECONDS);
        retained.discard().get(5, TimeUnit.SECONDS);
        assertFalse(restoration.isPending(retainedRecord.journalId()));
        service.completeRestoration(blockedRecord.journalId()).get(5, TimeUnit.SECONDS);
        service.completeRestoration(queuedRecord.journalId()).get(5, TimeUnit.SECONDS);
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    @Test
    void restorationClaimRequiresTheExactPersistedRecordNotOnlyItsId() throws Exception {
        RestorationJournal restoration = restorationJournal();
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 2);
        RecoveryDurabilityService.RestorationPreparation preparation =
                prepare(service, STRUCTURE, 70);
        RestorationRecord record = preparation.durableRecord().get(5, TimeUnit.SECONDS);
        RestorationRecord forged = new RestorationRecord(
                record.journalId(),
                record.sessionId(),
                "other",
                record.worldId(),
                record.worldName(),
                record.x(),
                record.y(),
                record.z(),
                record.expectedState(),
                record.originalState(),
                record.originalStructureBase64(),
                record.originalFingerprint(),
                record.releaseIdentity(),
                record.createdAtEpochMillis());

        assertFalse(preparation.claim(forged));
        assertTrue(preparation.claim(record));
        service.completeRestoration(record.journalId()).get(5, TimeUnit.SECONDS);
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    @Test
    void uncertainRestorationAppendRetainsEvidenceUntilRetryableDiscardSucceeds()
            throws Exception {
        AtomicInteger directoryForces = new AtomicInteger();
        RestorationJournal restoration = RestorationJournal.open(
                temporaryDirectory.toRealPath(),
                "test-release",
                UUID::randomUUID,
                System::currentTimeMillis,
                ignored -> {
                    if (directoryForces.incrementAndGet() == 1) {
                        throw new IOException("simulated post-rename directory fsync failure");
                    }
                });
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restoration, playerJournal(), temporaryDirectory, 2);
        RecoveryDurabilityService.RestorationPreparation preparation =
                prepare(service, STRUCTURE, 80);

        ExecutionException uncertain = assertThrows(
                ExecutionException.class,
                () -> preparation.durableRecord().get(5, TimeUnit.SECONDS));
        assertInstanceOf(
                RestorationJournalCommitUncertainException.class,
                uncertain.getCause());
        assertEquals(1, restoration.pendingCount());

        preparation.discard().get(5, TimeUnit.SECONDS);
        assertEquals(0, restoration.pendingCount());
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    @Test
    void uncertainPlayerAppendRetainsEvidenceUntilDiscardSucceeds() throws Exception {
        AtomicInteger directoryForces = new AtomicInteger();
        PlayerRecoveryJournal players = PlayerRecoveryJournal.open(
                temporaryDirectory.toRealPath(),
                ignored -> {
                    if (directoryForces.incrementAndGet() == 1) {
                        throw new IOException("simulated post-rename directory fsync failure");
                    }
                });
        RecoveryDurabilityService service =
                new RecoveryDurabilityService(
                        restorationJournal(), players, temporaryDirectory, 2);
        PlayerRecoveryRecord captured = playerRecord(
                UUID.fromString("40000000-0000-0000-0000-000000000002"));
        RecoveryDurabilityService.PlayerRecoveryPreparation preparation =
                service.preparePlayerRecovery(captured);

        ExecutionException uncertain = assertThrows(
                ExecutionException.class,
                () -> preparation.durableRecord().get(5, TimeUnit.SECONDS));
        assertInstanceOf(
                PlayerRecoveryJournalCommitUncertainException.class,
                uncertain.getCause());
        assertEquals(captured, players.pending(PLAYER_ID).orElseThrow());

        preparation.discard().get(5, TimeUnit.SECONDS);
        assertTrue(players.pending(PLAYER_ID).isEmpty());
        assertTrue(service.close(Duration.ofSeconds(5)));
    }

    private RestorationJournal restorationJournal() throws Exception {
        return RestorationJournal.open(temporaryDirectory.toRealPath(), "test-release");
    }

    private PlayerRecoveryJournal playerJournal() throws Exception {
        return PlayerRecoveryJournal.open(temporaryDirectory.toRealPath());
    }

    private static RecoveryDurabilityService.RestorationPreparation prepare(
            RecoveryDurabilityService service,
            byte[] structure,
            int x) {
        return service.prepareRestoration(
                SESSION_ID,
                "main",
                WORLD_ID,
                "world",
                x,
                70,
                -4,
                EXPECTED,
                ORIGINAL,
                structure);
    }

    private static PlayerRecoveryRecord playerRecord(UUID runId) {
        return new PlayerRecoveryRecord(
                PLAYER_ID,
                runId,
                "main",
                WORLD_ID,
                12.25,
                73.0,
                -9.75,
                45.0F,
                -12.0F,
                17.5,
                16,
                3.5F,
                0.25F,
                0.35F,
                true,
                true,
                false);
    }

    private static void awaitActiveWriter(RecoveryDurabilityService service) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int observedActive = service.status().active();
        while (observedActive == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            observedActive = service.status().active();
        }
        assertEquals(1, observedActive);
    }
}
