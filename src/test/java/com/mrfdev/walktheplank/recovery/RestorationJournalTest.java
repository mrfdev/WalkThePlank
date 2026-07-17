package com.mrfdev.walktheplank.recovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestorationJournalTest {
    private static final UUID JOURNAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_JOURNAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final byte[] STRUCTURE = {10, 0, 3, 1, 2, 3, 4, 5};

    @TempDir
    Path temporaryDirectory;

    @Test
    void durableRoundTripPreservesEveryRecordField() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        RestorationRecord written = append(journal, 10, 70, -4);

        RestorationJournal reopened = RestorationJournal.open(dataDirectory(), "new-release");
        RestorationRecord loaded = reopened.pendingRecords().getFirst();

        assertEquals(written, loaded);
        assertArrayEquals(STRUCTURE, loaded.originalStructure());
        assertEquals(1, reopened.pendingCount());
        assertTrue(Files.isRegularFile(
                dataDirectory().resolve("restoration-journal").resolve(JOURNAL_ID + ".pending")));
    }

    @Test
    void completionIsDurableAndIdempotent() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        append(journal, 10, 70, -4);

        journal.complete(JOURNAL_ID);
        journal.complete(JOURNAL_ID);

        assertEquals(0, journal.pendingCount());
        assertEquals(0, RestorationJournal.open(dataDirectory(), "release").pendingCount());
    }

    @Test
    void reportsPublishedRecordWhenPostRenameDirectoryFsyncFails() throws Exception {
        IOException fsyncFailure = new IOException("simulated directory fsync failure");
        RestorationJournal journal = RestorationJournal.open(
                dataDirectory(),
                "release",
                () -> JOURNAL_ID,
                () -> 1234L,
                ignored -> {
                    throw fsyncFailure;
                });

        RestorationJournalCommitUncertainException failure = assertThrows(
                RestorationJournalCommitUncertainException.class,
                () -> append(journal, 10, 70, -4));

        assertSame(fsyncFailure, failure.getCause());
        assertSame(failure.record(), journal.pendingRecords().getFirst());
        assertTrue(journal.isPending(failure.record().journalId()));
        assertTrue(Files.isRegularFile(
                journal.directory().resolve(failure.record().journalId() + ".pending")));

        RestorationJournal reopened = RestorationJournal.open(dataDirectory(), "release");
        assertEquals(failure.record(), reopened.pendingRecords().getFirst());
    }

    @Test
    void rejectsDuplicatePendingCoordinate() throws Exception {
        AtomicInteger nextId = new AtomicInteger();
        RestorationJournal journal = RestorationJournal.open(
                dataDirectory(),
                "release",
                () -> nextId.getAndIncrement() == 0 ? JOURNAL_ID : SECOND_JOURNAL_ID,
                () -> 1234L);
        append(journal, 10, 70, -4);

        assertThrows(IOException.class, () -> append(journal, 10, 70, -4));
        assertEquals(1, journal.pendingCount());
    }

    @Test
    void rejectsWholeRecordIntegrityFailure() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        append(journal, 10, 70, -4);
        Path file = journal.directory().resolve(JOURNAL_ID + ".pending");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        properties.setProperty("x", "11");
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, null);
        }

        assertThrows(IOException.class, () -> RestorationJournal.open(dataDirectory(), "release"));
    }

    @Test
    void refusesToFollowPendingRecordSymlink() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        Path external = temporaryDirectory.resolve("external-record");
        Files.writeString(external, "formatVersion=1\n");
        Files.createSymbolicLink(journal.directory().resolve(JOURNAL_ID + ".pending"), external);

        assertThrows(IOException.class, () -> RestorationJournal.open(dataDirectory(), "release"));
    }

    @Test
    void refusesToFollowJournalDirectorySymlink() throws Exception {
        Path dataDirectory = dataDirectory().resolve("plugin-data");
        Path externalDirectory = dataDirectory().resolve("external-journal");
        Files.createDirectory(dataDirectory);
        Files.createDirectory(externalDirectory);
        Files.createSymbolicLink(
                dataDirectory.resolve("restoration-journal"),
                externalDirectory);

        assertThrows(IOException.class, () -> RestorationJournal.open(dataDirectory, "release"));
    }

    @Test
    void refusesToTraverseSymbolicLinkInDataPath() throws Exception {
        Path realParent = dataDirectory().resolve("real-parent");
        Path linkedParent = dataDirectory().resolve("linked-parent");
        Files.createDirectory(realParent);
        Files.createSymbolicLink(linkedParent, realParent);

        assertThrows(
                IOException.class,
                () -> RestorationJournal.open(linkedParent.resolve("plugin-data"), "release"));
    }

    @Test
    void removesRecognizableStalePreCommitTemporaryFile() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        Path stale = journal.directory().resolve(JOURNAL_ID + ".123456.tmp");
        Files.writeString(stale, "incomplete");

        RestorationJournal reopened = RestorationJournal.open(dataDirectory(), "release");

        assertFalse(Files.exists(stale));
        assertEquals(0, reopened.pendingCount());
    }

    @Test
    void refusesUnknownTemporaryFileInsteadOfDeletingIt() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        Path unknown = journal.directory().resolve("operator-note.tmp");
        Files.writeString(unknown, "keep");

        assertThrows(IOException.class, () -> RestorationJournal.open(dataDirectory(), "release"));
        assertTrue(Files.exists(unknown));
    }

    @Test
    void recordsLastFailureWithoutRemovingPendingEntry() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        RestorationRecord record = append(journal, 10, 70, -4);

        journal.recordFailure(record, "world missing");

        RestorationFailure failure = journal.lastFailure().orElseThrow();
        assertEquals(record.journalId(), failure.journalId());
        assertEquals("world missing", failure.message());
        assertEquals(1, journal.pendingCount());
        assertFalse(journal.pendingArenaIds(java.util.Set.of(SESSION_ID)).contains("main"));
    }

    @Test
    void runtimeQueriesDoNotWaitForTheDiskMutationMonitor() throws Exception {
        RestorationJournal journal = deterministicJournal(JOURNAL_ID);
        RestorationRecord record = append(journal, 10, 70, -4);

        assertReadsDoNotWaitForMutationMonitor(journal, () -> {
            assertEquals(java.util.List.of(record), journal.pendingRecords());
            assertEquals(1, journal.pendingCount());
            assertTrue(journal.isPending(JOURNAL_ID));
            assertTrue(journal.hasPendingSession(SESSION_ID));
            assertEquals(java.util.Set.of("main"), journal.pendingArenaIds(java.util.Set.of()));
            assertTrue(journal.pendingArenaIds(java.util.Set.of(SESSION_ID)).isEmpty());
        });

        journal.complete(JOURNAL_ID);
        assertEquals(java.util.List.of(), journal.pendingRecords());
        assertEquals(0, journal.pendingCount());
        assertFalse(journal.isPending(JOURNAL_ID));
    }

    private RestorationJournal deterministicJournal(UUID journalId) throws IOException {
        return RestorationJournal.open(
                dataDirectory(),
                "v2.1.0 build 003 / release.jar",
                () -> journalId,
                () -> 1234L);
    }

    private Path dataDirectory() throws IOException {
        return temporaryDirectory.toRealPath();
    }

    private static RestorationRecord append(
            RestorationJournal journal,
            int x,
            int y,
            int z) throws IOException {
        return journal.append(
                SESSION_ID,
                "main",
                WORLD_ID,
                "world",
                x,
                y,
                z,
                new SerializedBlockState("EMERALD_BLOCK", "minecraft:emerald_block"),
                new SerializedBlockState("AIR", "minecraft:air"),
                STRUCTURE);
    }

    private static void assertReadsDoNotWaitForMutationMonitor(
            Object monitor,
            CheckedRead reads) throws Exception {
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform()
                .name("restoration-journal-monitor-holder")
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
                .name("restoration-journal-snapshot-reader")
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
}
