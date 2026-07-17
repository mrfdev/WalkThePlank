package com.mrfdev.walktheplank.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerRecoveryJournalTest {
    private static final UUID PLAYER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_PLAYER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID WORLD_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @TempDir
    Path temporaryDirectory;

    @Test
    void durableRoundTripContainsOnlyBoundedOwnershipStateAndWorldUuid() throws Exception {
        PlayerRecoveryJournal journal = journal();
        PlayerRecoveryRecord record = record(PLAYER_ID, RUN_ID);

        assertEquals(record, journal.append(record));
        assertEquals(record, journal.append(record));
        assertTrue(journal.requiresRecovery(PLAYER_ID));
        assertFalse(journal.requiresRecovery(SECOND_PLAYER_ID));

        PlayerRecoveryJournal reopened = PlayerRecoveryJournal.open(dataDirectory());
        assertEquals(record, reopened.pending(PLAYER_ID).orElseThrow());
        assertEquals(1, reopened.pendingCount());
        assertTrue(reopened.health().healthy());

        String stored = Files.readString(recordFile(PLAYER_ID));
        assertFalse(stored.contains("playerName"));
        assertFalse(stored.contains("worldName"));
        assertFalse(stored.contains("inventory"));
        assertFalse(stored.contains("ExamplePlayer"));
    }

    @Test
    void completionRequiresExactOwnershipAndIsDurablyIdempotent() throws Exception {
        PlayerRecoveryJournal journal = journal();
        journal.append(record(PLAYER_ID, RUN_ID));

        assertThrows(IOException.class, () -> journal.complete(PLAYER_ID, SECOND_RUN_ID, "main"));
        assertThrows(IOException.class, () -> journal.complete(PLAYER_ID, RUN_ID, "other"));
        assertEquals(1, journal.pendingCount());

        journal.complete(PLAYER_ID, RUN_ID, "main");
        journal.complete(PLAYER_ID, RUN_ID, "main");

        assertEquals(0, journal.pendingCount());
        assertFalse(journal.requiresRecovery(PLAYER_ID));
        assertEquals(0, PlayerRecoveryJournal.open(dataDirectory()).pendingCount());
    }

    @Test
    void reportsPublishedRecordWhenPostRenameDirectoryFsyncFails() throws Exception {
        IOException fsyncFailure = new IOException("simulated directory fsync failure");
        PlayerRecoveryJournal journal = PlayerRecoveryJournal.open(
                dataDirectory(),
                ignored -> {
                    throw fsyncFailure;
                });
        PlayerRecoveryRecord record = record(PLAYER_ID, RUN_ID);

        PlayerRecoveryJournalCommitUncertainException failure = assertThrows(
                PlayerRecoveryJournalCommitUncertainException.class,
                () -> journal.append(record));

        assertSame(fsyncFailure, failure.getCause());
        assertSame(record, failure.record());
        assertSame(record, journal.pending(PLAYER_ID).orElseThrow());
        assertTrue(journal.requiresRecovery(PLAYER_ID));
        assertFalse(journal.health().writesAvailable());
        assertTrue(Files.isRegularFile(recordFile(PLAYER_ID)));

        PlayerRecoveryJournal reopened = PlayerRecoveryJournal.open(dataDirectory());
        assertEquals(record, reopened.pending(PLAYER_ID).orElseThrow());
    }

    @Test
    void refusesConflictingPlayerAndRunOwnership() throws Exception {
        PlayerRecoveryJournal journal = journal();
        journal.append(record(PLAYER_ID, RUN_ID));

        assertThrows(IOException.class, () -> journal.append(record(PLAYER_ID, SECOND_RUN_ID)));
        assertThrows(IOException.class, () -> journal.append(record(SECOND_PLAYER_ID, RUN_ID)));
        assertEquals(1, journal.pendingCount());
    }

    @Test
    void tamperingIsRetainedAndDegradesOnlyTheIdentifiableOwner() throws Exception {
        PlayerRecoveryJournal journal = journal();
        journal.append(record(PLAYER_ID, RUN_ID));
        Properties properties = load(recordFile(PLAYER_ID));
        properties.setProperty("state.health", "19.0");
        store(recordFile(PLAYER_ID), properties);

        PlayerRecoveryJournal reopened = PlayerRecoveryJournal.open(dataDirectory());

        assertFalse(reopened.health().healthy());
        assertEquals(1, reopened.health().invalidRecords());
        assertEquals("invalid_record", reopened.health().lastFailureCode().orElseThrow());
        assertTrue(reopened.pending(PLAYER_ID).isEmpty());
        assertTrue(reopened.requiresRecovery(PLAYER_ID));
        assertFalse(reopened.canSafelyRecord(PLAYER_ID));
        assertTrue(reopened.canSafelyRecord(SECOND_PLAYER_ID));
        assertTrue(Files.exists(recordFile(PLAYER_ID)));
    }

    @Test
    void unknownPropertyIsStrictlyRejectedWithoutDeletingEvidence() throws Exception {
        PlayerRecoveryJournal journal = journal();
        journal.append(record(PLAYER_ID, RUN_ID));
        Properties properties = load(recordFile(PLAYER_ID));
        properties.setProperty("player.name", "must-not-exist");
        store(recordFile(PLAYER_ID), properties);

        PlayerRecoveryJournal reopened = PlayerRecoveryJournal.open(dataDirectory());

        assertEquals(1, reopened.pendingCount());
        assertEquals(1, reopened.health().invalidRecords());
        assertTrue(Files.exists(recordFile(PLAYER_ID)));
    }

    @Test
    void malformedUnknownFilenameFailsClosedForNewWrites() throws Exception {
        PlayerRecoveryJournal journal = journal();
        Path unknown = journal.directory().resolve("not-a-uuid.pending");
        Files.writeString(unknown, "formatVersion=1\n");

        PlayerRecoveryJournal reopened = PlayerRecoveryJournal.open(dataDirectory());

        assertFalse(reopened.health().healthy());
        assertFalse(reopened.health().writesAvailable());
        assertFalse(reopened.canSafelyRecord(SECOND_PLAYER_ID));
        assertTrue(Files.exists(unknown));
    }

    @Test
    void refusesPendingRecordSymlinkWithoutFollowingIt() throws Exception {
        PlayerRecoveryJournal journal = journal();
        Path external = temporaryDirectory.resolve("external-record");
        Files.writeString(external, "formatVersion=1\n");
        Files.createSymbolicLink(recordFile(PLAYER_ID), external);

        PlayerRecoveryJournal reopened = PlayerRecoveryJournal.open(dataDirectory());

        assertEquals(1, reopened.health().invalidRecords());
        assertFalse(reopened.canSafelyRecord(PLAYER_ID));
        assertEquals("formatVersion=1\n", Files.readString(external));
    }

    @Test
    void refusesJournalDirectorySymlink() throws Exception {
        Path data = temporaryDirectory.resolve("plugin-data");
        Path external = temporaryDirectory.resolve("external");
        Files.createDirectory(data);
        Files.createDirectory(external);
        Files.createSymbolicLink(data.resolve("player-recovery-journal"), external);

        assertThrows(IOException.class, () -> PlayerRecoveryJournal.open(data));
    }

    @Test
    void removesOnlyRecognizableStaleTemporaryFiles() throws Exception {
        PlayerRecoveryJournal journal = journal();
        Path stale = journal.directory().resolve(PLAYER_ID + ".123.tmp");
        Files.writeString(stale, "incomplete");

        PlayerRecoveryJournal reopened = PlayerRecoveryJournal.open(dataDirectory());

        assertFalse(Files.exists(stale));
        assertEquals(0, reopened.pendingCount());

        Path unknown = reopened.directory().resolve("operator-note.tmp");
        Files.writeString(unknown, "keep");
        assertThrows(IOException.class, () -> PlayerRecoveryJournal.open(dataDirectory()));
        assertTrue(Files.exists(unknown));
    }

    @Test
    void recordRejectsNonFiniteOutOfRangeOrContradictoryState() {
        PlayerRecoveryRecord baseline = record(PLAYER_ID, RUN_ID);
        assertThrows(IllegalArgumentException.class, () -> withReturnX(baseline, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> withReturnX(baseline, 30_000_001.0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerRecoveryRecord(
                PLAYER_ID, RUN_ID, "main", WORLD_ID,
                1.0, 64.0, 2.0, 0.0F, 91.0F,
                20.0, 20, 5.0F, 0.0F, 0.2F,
                false, false, true));
        assertThrows(IllegalArgumentException.class, () -> new PlayerRecoveryRecord(
                PLAYER_ID, RUN_ID, "main", WORLD_ID,
                1.0, 64.0, 2.0, 0.0F, 0.0F,
                20.0, 20, 5.0F, 0.0F, 0.2F,
                false, true, true));
    }

    @Test
    void runtimeQueriesDoNotWaitForTheDiskMutationMonitor() throws Exception {
        PlayerRecoveryJournal journal = journal();
        PlayerRecoveryRecord record = record(PLAYER_ID, RUN_ID);
        journal.append(record);

        assertReadsDoNotWaitForMutationMonitor(journal, () -> {
            assertEquals(record, journal.pending(PLAYER_ID).orElseThrow());
            assertTrue(journal.requiresRecovery(PLAYER_ID));
            assertFalse(journal.requiresRecovery(SECOND_PLAYER_ID));
            assertEquals(java.util.List.of(record), journal.pendingRecords());
            assertEquals(1, journal.pendingCount());
            assertFalse(journal.canSafelyRecord(PLAYER_ID));
            assertTrue(journal.canSafelyRecord(SECOND_PLAYER_ID));
            assertEquals(new PlayerRecoveryJournal.Health(
                    true, 1, 0, true, java.util.Optional.empty()), journal.health());
        });

        journal.complete(PLAYER_ID, RUN_ID, "main");
        assertTrue(journal.pending(PLAYER_ID).isEmpty());
        assertEquals(java.util.List.of(), journal.pendingRecords());
        assertEquals(0, journal.pendingCount());
        assertTrue(journal.canSafelyRecord(PLAYER_ID));
    }

    private PlayerRecoveryJournal journal() throws IOException {
        return PlayerRecoveryJournal.open(dataDirectory());
    }

    private Path dataDirectory() throws IOException {
        return temporaryDirectory.toRealPath();
    }

    private Path recordFile(UUID playerId) {
        return temporaryDirectory.resolve("player-recovery-journal").resolve(playerId + ".pending");
    }

    private static PlayerRecoveryRecord record(UUID playerId, UUID runId) {
        return new PlayerRecoveryRecord(
                playerId,
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

    private static PlayerRecoveryRecord withReturnX(PlayerRecoveryRecord record, double returnX) {
        return new PlayerRecoveryRecord(
                record.playerId(), record.runId(), record.arenaId(), record.returnWorldId(),
                returnX, record.returnY(), record.returnZ(), record.returnYaw(), record.returnPitch(),
                record.health(), record.foodLevel(), record.saturation(), record.exhaustion(), record.walkSpeed(),
                record.allowFlight(), record.flying(), record.collidable());
    }

    private static void assertReadsDoNotWaitForMutationMonitor(
            Object monitor,
            CheckedRead reads) throws Exception {
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform()
                .name("player-recovery-journal-monitor-holder")
                .daemon(true)
                .start(() -> {
                    synchronized (monitor) {
                        monitorHeld.countDown();
                        awaitUnchecked(releaseMonitor);
                    }
                });
        assertTrue(monitorHeld.await(2, TimeUnit.SECONDS));

        FutureTask<Void> query = new FutureTask<>(() -> {
            reads.run();
            return null;
        });
        Thread reader = Thread.ofPlatform()
                .name("player-recovery-journal-snapshot-reader")
                .daemon(true)
                .start(query);
        try {
            query.get(2, TimeUnit.SECONDS);
        } finally {
            releaseMonitor.countDown();
            holder.join(2_000L);
            reader.join(2_000L);
        }
        assertFalse(holder.isAlive());
        assertFalse(reader.isAlive());
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding the mutation monitor", exception);
        }
    }

    @FunctionalInterface
    private interface CheckedRead {
        void run() throws Exception;
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void store(Path path, Properties properties) throws IOException {
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, null);
        }
    }
}
