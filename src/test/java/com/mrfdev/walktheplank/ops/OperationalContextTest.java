package com.mrfdev.walktheplank.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationalContextTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T12:34:56.789Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void closeDrainsFifoAuditEntriesPreparedAsImmutableData() throws Exception {
        StructuredAuditLog auditLog = StructuredAuditLog.open(
                temporaryDirectory, "v2.2.0 / build 006", 16_384, 3, FIXED_CLOCK);
        OperationsIoWorker worker = new OperationsIoWorker(16);
        OperationalContext context = new OperationalContext(
                new OperationalMetrics(), auditLog, worker, Logger.getAnonymousLogger());
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        context.submitRequired(() -> {
            blockerStarted.countDown();
            assertTrue(releaseBlocker.await(2, TimeUnit.SECONDS));
            return null;
        });
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

        Map<String, Object> mutableFields = new HashMap<>();
        mutableFields.put("sequence", 1);
        mutableFields.put("note", "captured");
        context.audit("admin.validate", null, null, mutableFields);
        mutableFields.put("sequence", 99);
        mutableFields.put("note", "mutated");
        context.audit("admin.validate", null, null, Map.of("sequence", 2));

        releaseBlocker.countDown();
        assertTrue(context.close(Duration.ofSeconds(2)));

        List<String> lines = Files.readAllLines(auditLog.currentFile());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"sequence\":1"));
        assertTrue(lines.get(0).contains("\"note\":\"captured\""));
        assertFalse(lines.get(0).contains("mutated"));
        assertTrue(lines.get(1).contains("\"sequence\":2"));
        assertEquals(0, context.metrics().snapshot().auditFailures());
        assertEquals(2, context.ioStatus().bestEffortAccepted());
        assertTrue(context.ioStatus().terminated());
    }

    @Test
    void invalidAuditDataIsRejectedBeforeItCanEnterTheWorker() throws Exception {
        StructuredAuditLog auditLog = StructuredAuditLog.open(
                temporaryDirectory, "v2.2.0 / build 006", 16_384, 3, FIXED_CLOCK);
        OperationsIoWorker worker = new OperationsIoWorker(4);
        OperationalContext context = new OperationalContext(
                new OperationalMetrics(), auditLog, worker, Logger.getAnonymousLogger());

        assertThrows(
                IllegalArgumentException.class,
                () -> context.audit("admin.validate", null, null, Map.of(
                        "nested", Map.of("unsafe", true))));
        assertEquals(0, context.ioStatus().bestEffortAccepted());
        assertTrue(context.close(Duration.ofSeconds(2)));
        assertEquals(0, Files.size(auditLog.currentFile()));
    }
}
