package com.mrfdev.walktheplank.game;

import java.util.Objects;

/**
 * Tracks one successor while its immutable durability record is prepared off-thread.
 *
 * <p>Completing durability only makes the successor eligible for a future landing commit. It must
 * not make the successor visible in the world. The primary-thread landing path consumes the exact
 * ready pair immediately before it restores the departed platform and places the next one.</p>
 */
final class SuccessorPipeline<P, R> {
    private P preparation;
    private R durableRecord;

    void begin(P prepared) {
        if (preparation != null) {
            throw new IllegalStateException("A successor is already prepared");
        }
        preparation = Objects.requireNonNull(prepared, "prepared");
    }

    void complete(P expected, R record) {
        if (preparation != Objects.requireNonNull(expected, "expected")) {
            throw new IllegalStateException("Successor durability completion is stale");
        }
        if (durableRecord != null) {
            throw new IllegalStateException("Successor durability is already complete");
        }
        durableRecord = Objects.requireNonNull(record, "record");
    }

    Ready<P, R> ready() {
        return preparation == null || durableRecord == null
                ? null
                : new Ready<>(preparation, durableRecord);
    }

    Ready<P, R> consume(Ready<P, R> expected) {
        Objects.requireNonNull(expected, "expected");
        if (preparation != expected.preparation() || durableRecord != expected.record()) {
            throw new IllegalStateException("Successor landing commit is stale");
        }
        Ready<P, R> consumed = new Ready<>(preparation, durableRecord);
        preparation = null;
        durableRecord = null;
        return consumed;
    }

    P clear() {
        P cleared = preparation;
        preparation = null;
        durableRecord = null;
        return cleared;
    }

    boolean isEmpty() {
        return preparation == null;
    }

    record Ready<P, R>(P preparation, R record) {
        Ready {
            Objects.requireNonNull(preparation, "preparation");
            Objects.requireNonNull(record, "record");
        }
    }
}
