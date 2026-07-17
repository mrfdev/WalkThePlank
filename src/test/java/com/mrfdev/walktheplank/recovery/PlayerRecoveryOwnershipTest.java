package com.mrfdev.walktheplank.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import com.mrfdev.walktheplank.database.RunStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerRecoveryOwnershipTest {
    private static final UUID PLAYER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant STARTED = Instant.parse("2026-07-14T10:00:00Z");

    @Test
    void acceptsExactOwnershipForEveryLifecycleStatus() {
        PlayerRecoveryRecord recovery = recovery();
        for (RunStatus status : RunStatus.values()) {
            assertEquals(
                    PlayerRecoveryOwnership.Decision.VERIFIED,
                    PlayerRecoveryOwnership.verify(recovery, Optional.of(run(status))));
        }
    }

    @Test
    void rejectsMissingOrMismatchedRetainedEvidence() {
        PlayerRecoveryRecord recovery = recovery();
        assertEquals(
                PlayerRecoveryOwnership.Decision.RUN_MISSING,
                PlayerRecoveryOwnership.verify(recovery, Optional.empty()));
        assertEquals(
                PlayerRecoveryOwnership.Decision.RUN_ID_MISMATCH,
                PlayerRecoveryOwnership.verify(recovery, Optional.of(copyRun(
                        UUID.fromString("20000000-0000-0000-0000-000000000002"), PLAYER_ID, "main"))));
        assertEquals(
                PlayerRecoveryOwnership.Decision.PLAYER_MISMATCH,
                PlayerRecoveryOwnership.verify(recovery, Optional.of(copyRun(
                        RUN_ID, UUID.fromString("10000000-0000-0000-0000-000000000002"), "main"))));
        assertEquals(
                PlayerRecoveryOwnership.Decision.ARENA_MISMATCH,
                PlayerRecoveryOwnership.verify(recovery, Optional.of(copyRun(RUN_ID, PLAYER_ID, "other"))));
    }

    @Test
    void terminalDeathAndTeleportNeverReplaySupersededState() {
        assertEquals(
                PlayerRecoveryOwnership.RecoveryAction.RESTORE_TEMPORARY_STATE_ONLY,
                PlayerRecoveryOwnership.recoveryAction(completedRun("DEATH")));
        assertEquals(
                PlayerRecoveryOwnership.RecoveryAction.RESTORE_STATE_ONLY,
                PlayerRecoveryOwnership.recoveryAction(completedRun("TELEPORT")));
        assertEquals(
                PlayerRecoveryOwnership.RecoveryAction.RESTORE_STATE_AND_RETURN,
                PlayerRecoveryOwnership.recoveryAction(completedRun("LEAVE")));
        assertEquals(
                PlayerRecoveryOwnership.RecoveryAction.RESTORE_STATE_AND_RETURN,
                PlayerRecoveryOwnership.recoveryAction(copyRun(RUN_ID, PLAYER_ID, "main")));
    }

    private static PlayerRecoveryRecord recovery() {
        return new PlayerRecoveryRecord(
                PLAYER_ID, RUN_ID, "main", WORLD_ID,
                1.0, 64.0, 2.0, 0.0F, 0.0F,
                20.0, 20, 5.0F, 0.0F, 0.2F,
                false, false, true);
    }

    private static RunInvestigationRecord run(RunStatus status) {
        Optional<Instant> ended = status == RunStatus.STARTED
                ? Optional.empty()
                : Optional.of(STARTED.plusSeconds(30));
        Optional<Integer> score = status == RunStatus.COMPLETED ? Optional.of(4) : Optional.empty();
        Optional<String> reason = status == RunStatus.STARTED
                ? Optional.empty()
                : Optional.of(status == RunStatus.COMPLETED ? "LEAVE" : "RECOVERY");
        return new RunInvestigationRecord(
                1L, RUN_ID, PLAYER_ID, "main", STARTED, ended, score, reason,
                "release", Optional.empty(), status, Optional.empty(), Optional.empty());
    }

    private static RunInvestigationRecord copyRun(UUID runId, UUID playerId, String arenaId) {
        return new RunInvestigationRecord(
                1L, runId, playerId, arenaId, STARTED, Optional.empty(), Optional.empty(), Optional.empty(),
                "release", Optional.empty(), RunStatus.STARTED, Optional.empty(), Optional.empty());
    }

    private static RunInvestigationRecord completedRun(String reason) {
        return new RunInvestigationRecord(
                1L, RUN_ID, PLAYER_ID, "main", STARTED, Optional.of(STARTED.plusSeconds(30)),
                Optional.of(4), Optional.of(reason), "release", Optional.empty(), RunStatus.COMPLETED,
                Optional.empty(), Optional.empty());
    }
}
