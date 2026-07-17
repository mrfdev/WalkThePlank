package com.mrfdev.walktheplank.recovery;

import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import java.util.Objects;
import java.util.Optional;

/** Exact retained-run ownership gate for applying persisted player state. */
public final class PlayerRecoveryOwnership {
    private PlayerRecoveryOwnership() {
    }

    public static Decision verify(
            PlayerRecoveryRecord recovery,
            Optional<RunInvestigationRecord> retainedRun) {
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(retainedRun, "retainedRun");
        if (retainedRun.isEmpty()) {
            return Decision.RUN_MISSING;
        }
        RunInvestigationRecord run = retainedRun.orElseThrow();
        if (!run.runId().equals(recovery.runId())) {
            return Decision.RUN_ID_MISMATCH;
        }
        if (!run.playerId().equals(recovery.playerId())) {
            return Decision.PLAYER_MISMATCH;
        }
        if (!run.arenaId().equals(recovery.arenaId())) {
            return Decision.ARENA_MISMATCH;
        }
        return Decision.VERIFIED;
    }

    /**
     * Chooses how a verified journal entry may be replayed. A terminal external teleport already
     * established the player's destination, while a death/respawn already established health,
     * hunger, and destination. Replaying either would undo legitimate server state.
     */
    public static RecoveryAction recoveryAction(RunInvestigationRecord retainedRun) {
        Objects.requireNonNull(retainedRun, "retainedRun");
        return retainedRun.endReason()
                .map(reason -> switch (reason) {
                    case "DEATH" -> RecoveryAction.RESTORE_TEMPORARY_STATE_ONLY;
                    case "TELEPORT" -> RecoveryAction.RESTORE_STATE_ONLY;
                    default -> RecoveryAction.RESTORE_STATE_AND_RETURN;
                })
                .orElse(RecoveryAction.RESTORE_STATE_AND_RETURN);
    }

    public enum Decision {
        VERIFIED,
        RUN_MISSING,
        RUN_ID_MISMATCH,
        PLAYER_MISMATCH,
        ARENA_MISMATCH
    }

    public enum RecoveryAction {
        RESTORE_STATE_AND_RETURN,
        RESTORE_STATE_ONLY,
        RESTORE_TEMPORARY_STATE_ONLY
    }
}
