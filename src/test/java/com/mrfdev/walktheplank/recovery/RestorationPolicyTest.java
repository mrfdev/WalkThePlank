package com.mrfdev.walktheplank.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RestorationPolicyTest {
    private static final SerializedBlockState EXPECTED =
            new SerializedBlockState("EMERALD_BLOCK", "minecraft:emerald_block");
    private static final SerializedBlockState ORIGINAL =
            new SerializedBlockState("AIR", "minecraft:air");
    private static final byte[] STRUCTURE = {10, 0, 1, 2, 3};
    private static final RestorationRecord RECORD = RestorationRecord.create(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            "main",
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            "world",
            1,
            70,
            2,
            EXPECTED,
            ORIGINAL,
            STRUCTURE,
            "v2.1.0 build 003",
            1234L);

    @Test
    void onlyExpectedPluginStateIsEligibleForMutation() {
        assertEquals(
                RestorationDecision.RESTORE_EXPECTED,
                RestorationPolicy.decide(
                        RECORD,
                        Optional.of(EXPECTED),
                        Optional.of("unneeded-for-expected-state")));
        assertEquals(
                RestorationDecision.CONFLICT,
                RestorationPolicy.decide(
                        RECORD,
                        Optional.of(new SerializedBlockState("STONE", "minecraft:stone")),
                        Optional.of(RECORD.originalFingerprint())));
    }

    @Test
    void exactOriginalSnapshotIsIdempotentlyComplete() {
        assertEquals(
                RestorationDecision.ALREADY_RESTORED,
                RestorationPolicy.decide(
                        RECORD,
                        Optional.of(ORIGINAL),
                        Optional.of(RECORD.originalFingerprint())));
        assertEquals(
                RestorationDecision.CONFLICT,
                RestorationPolicy.decide(
                        RECORD,
                        Optional.of(ORIGINAL),
                        Optional.of("different-full-snapshot")));
    }

    @Test
    void missingWorldNeverRestores() {
        assertEquals(
                RestorationDecision.WORLD_MISSING,
                RestorationPolicy.decide(RECORD, Optional.empty(), Optional.empty()));
    }

    @Test
    void expectedStateWinsWhenExpectedAndOriginalTopLevelStateMatch() {
        RestorationRecord sameTopLevelState = RestorationRecord.create(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                RECORD.sessionId(),
                RECORD.arenaId(),
                RECORD.worldId(),
                RECORD.worldName(),
                RECORD.x(),
                RECORD.y(),
                RECORD.z(),
                ORIGINAL,
                ORIGINAL,
                STRUCTURE,
                RECORD.releaseIdentity(),
                RECORD.createdAtEpochMillis());

        assertEquals(
                RestorationDecision.RESTORE_EXPECTED,
                RestorationPolicy.decide(
                        sameTopLevelState,
                        Optional.of(ORIGINAL),
                        Optional.of(sameTopLevelState.originalFingerprint())));
    }
}
