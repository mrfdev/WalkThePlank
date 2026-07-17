package com.mrfdev.walktheplank.recovery;

import java.io.IOException;
import java.io.Serial;
import java.util.Objects;

/**
 * Signals that a player-recovery record was atomically published, but its parent-directory fsync
 * failed.
 *
 * <p>The record remains retained in the journal's published state and may already survive a
 * process crash. Callers must therefore treat this as an uncertain durable commit, not as a clean
 * append failure.</p>
 */
public final class PlayerRecoveryJournalCommitUncertainException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient PlayerRecoveryRecord record;

    PlayerRecoveryJournalCommitUncertainException(PlayerRecoveryRecord record, IOException cause) {
        super(
                "Player-recovery record was published, but directory durability could not be confirmed",
                Objects.requireNonNull(cause, "cause"));
        this.record = Objects.requireNonNull(record, "record");
    }

    /** The exact record retained in the journal's published in-memory state. */
    public PlayerRecoveryRecord record() {
        return record;
    }
}
