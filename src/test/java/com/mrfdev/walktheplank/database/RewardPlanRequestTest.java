package com.mrfdev.walktheplank.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RewardPlanRequestTest {
    private static final RewardStepSpec STEP = new RewardStepSpec("give", "a".repeat(64));

    @Test
    void acceptsExactlyTheDurableStepLimitAndRejectsOneMore() {
        assertDoesNotThrow(() -> request(Collections.nCopies(RewardPlanRequest.MAX_STEPS, STEP)));
        assertThrows(
                IllegalArgumentException.class,
                () -> request(Collections.nCopies(RewardPlanRequest.MAX_STEPS + 1, STEP)));
    }

    private static RewardPlanRequest request(List<RewardStepSpec> steps) {
        return new RewardPlanRequest(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "step-limit",
                Instant.parse("2026-07-14T10:00:00Z"),
                steps);
    }
}
