package com.mrfdev.walktheplank.recovery;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies the exact player-recovery record after its durable write completes.
 *
 * <p>A newly published record intentionally makes {@link PlayerRecoveryJournal#canSafelyRecord}
 * return false. Post-durability activation must therefore verify ownership of that record instead
 * of applying the pre-write "no record exists" gate again.</p>
 */
public final class PlayerRecoveryActivationPolicy {
    private PlayerRecoveryActivationPolicy() {
    }

    public static Decision verify(
            boolean writesAvailable,
            PlayerRecoveryRecord durableRecord,
            Optional<PlayerRecoveryRecord> publishedRecord,
            UUID expectedPlayerId,
            UUID expectedRunId,
            String expectedArenaId) {
        Objects.requireNonNull(durableRecord, "durableRecord");
        Objects.requireNonNull(publishedRecord, "publishedRecord");
        Objects.requireNonNull(expectedPlayerId, "expectedPlayerId");
        Objects.requireNonNull(expectedRunId, "expectedRunId");
        Objects.requireNonNull(expectedArenaId, "expectedArenaId");
        if (!writesAvailable) {
            return Decision.WRITES_UNAVAILABLE;
        }
        if (!durableRecord.owns(expectedPlayerId, expectedRunId, expectedArenaId)) {
            return Decision.DURABLE_OWNERSHIP_MISMATCH;
        }
        if (publishedRecord.isEmpty()) {
            return Decision.RECORD_NOT_PUBLISHED;
        }
        if (!publishedRecord.orElseThrow().equals(durableRecord)) {
            return Decision.PUBLISHED_RECORD_MISMATCH;
        }
        return Decision.VERIFIED;
    }

    public enum Decision {
        VERIFIED,
        WRITES_UNAVAILABLE,
        DURABLE_OWNERSHIP_MISMATCH,
        RECORD_NOT_PUBLISHED,
        PUBLISHED_RECORD_MISMATCH
    }
}
