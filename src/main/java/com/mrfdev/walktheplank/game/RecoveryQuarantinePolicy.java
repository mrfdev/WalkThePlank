package com.mrfdev.walktheplank.game;

/** Separates active write-ahead evidence from orphaned crash-recovery evidence. */
final class RecoveryQuarantinePolicy {
    private RecoveryQuarantinePolicy() {
    }

    static boolean requiresQuarantine(
            boolean liveRunOwnsEvidence,
            boolean pendingRecoveryEvidence,
            boolean ownershipLookupInProgress) {
        return !liveRunOwnsEvidence && (pendingRecoveryEvidence || ownershipLookupInProgress);
    }
}
