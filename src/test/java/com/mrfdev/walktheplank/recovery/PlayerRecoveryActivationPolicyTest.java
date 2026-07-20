package com.mrfdev.walktheplank.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlayerRecoveryActivationPolicyTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Test
    void acceptsTheExactPublishedRecordAfterDurability() {
        PlayerRecoveryRecord recovery = recovery(PLAYER_ID, RUN_ID, "main");

        assertEquals(
                PlayerRecoveryActivationPolicy.Decision.VERIFIED,
                PlayerRecoveryActivationPolicy.verify(
                        true,
                        recovery,
                        Optional.of(recovery),
                        PLAYER_ID,
                        RUN_ID,
                        "main"));
    }

    @Test
    void rejectsUnavailableOrMismatchedDurableEvidence() {
        PlayerRecoveryRecord recovery = recovery(PLAYER_ID, RUN_ID, "main");

        assertEquals(
                PlayerRecoveryActivationPolicy.Decision.WRITES_UNAVAILABLE,
                PlayerRecoveryActivationPolicy.verify(
                        false, recovery, Optional.of(recovery), PLAYER_ID, RUN_ID, "main"));
        assertEquals(
                PlayerRecoveryActivationPolicy.Decision.DURABLE_OWNERSHIP_MISMATCH,
                PlayerRecoveryActivationPolicy.verify(
                        true, recovery, Optional.of(recovery), PLAYER_ID, RUN_ID, "other"));
        assertEquals(
                PlayerRecoveryActivationPolicy.Decision.RECORD_NOT_PUBLISHED,
                PlayerRecoveryActivationPolicy.verify(
                        true, recovery, Optional.empty(), PLAYER_ID, RUN_ID, "main"));
        assertEquals(
                PlayerRecoveryActivationPolicy.Decision.PUBLISHED_RECORD_MISMATCH,
                PlayerRecoveryActivationPolicy.verify(
                        true,
                        recovery,
                        Optional.of(recovery(PLAYER_ID, RUN_ID, "other")),
                        PLAYER_ID,
                        RUN_ID,
                        "main"));
    }

    private static PlayerRecoveryRecord recovery(UUID playerId, UUID runId, String arenaId) {
        return new PlayerRecoveryRecord(
                playerId,
                runId,
                arenaId,
                WORLD_ID,
                1.0,
                64.0,
                2.0,
                0.0F,
                0.0F,
                20.0,
                20,
                5.0F,
                0.0F,
                0.2F,
                false,
                false,
                true);
    }
}
