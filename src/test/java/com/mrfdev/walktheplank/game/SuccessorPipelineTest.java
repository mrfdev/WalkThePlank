package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SuccessorPipelineTest {
    @Test
    void durabilityCompletionKeepsTheSuccessorHiddenUntilLandingConsumesIt() {
        SuccessorPipeline<Object, Object> pipeline = new SuccessorPipeline<>();
        Object preparation = new Object();
        Object record = new Object();

        pipeline.begin(preparation);
        assertNull(pipeline.ready(), "pending durability must not be presented as a landing commit");

        pipeline.complete(preparation, record);
        SuccessorPipeline.Ready<Object, Object> ready = pipeline.ready();

        assertSame(preparation, ready.preparation());
        assertSame(record, ready.record());
        assertFalse(pipeline.isEmpty());

        SuccessorPipeline.Ready<Object, Object> consumed = pipeline.consume(ready);
        assertSame(preparation, consumed.preparation());
        assertSame(record, consumed.record());
        assertTrue(pipeline.isEmpty());
        assertNull(pipeline.ready());
    }

    @Test
    void staleDurabilityAndLandingCallbacksCannotCommitAnotherSuccessor() {
        SuccessorPipeline<Object, Object> pipeline = new SuccessorPipeline<>();
        Object preparation = new Object();
        Object record = new Object();
        pipeline.begin(preparation);

        assertThrows(
                IllegalStateException.class,
                () -> pipeline.complete(new Object(), record));

        pipeline.complete(preparation, record);
        SuccessorPipeline.Ready<Object, Object> ready = pipeline.ready();
        assertThrows(
                IllegalStateException.class,
                () -> pipeline.consume(new SuccessorPipeline.Ready<>(
                        preparation,
                        new Object())));

        pipeline.consume(ready);
        assertThrows(IllegalStateException.class, () -> pipeline.consume(ready));
    }

    @Test
    void endingARunClearsEitherPendingOrDurableHiddenPreparation() {
        SuccessorPipeline<Object, Object> pending = new SuccessorPipeline<>();
        Object pendingPreparation = new Object();
        pending.begin(pendingPreparation);
        assertSame(pendingPreparation, pending.clear());
        assertTrue(pending.isEmpty());

        SuccessorPipeline<Object, Object> durable = new SuccessorPipeline<>();
        Object durablePreparation = new Object();
        durable.begin(durablePreparation);
        durable.complete(durablePreparation, new Object());
        assertSame(durablePreparation, durable.clear());
        assertTrue(durable.isEmpty());
        assertNull(durable.ready());
    }
}
