package com.mrfdev.walktheplank.recovery;

import java.util.Objects;
import java.util.Optional;

/** Pure recovery decision policy; it never mutates a world or journal. */
public final class RestorationPolicy {
    private RestorationPolicy() {
    }

    public static RestorationDecision decide(
            RestorationRecord record,
            Optional<SerializedBlockState> currentState,
            Optional<String> currentFingerprint) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(currentFingerprint, "currentFingerprint");
        if (currentState.isEmpty()) {
            return RestorationDecision.WORLD_MISSING;
        }

        SerializedBlockState current = currentState.orElseThrow();
        if (record.expectedState().equals(current)) {
            return RestorationDecision.RESTORE_EXPECTED;
        }
        if (record.originalState().equals(current)
                && currentFingerprint.filter(record.originalFingerprint()::equals).isPresent()) {
            return RestorationDecision.ALREADY_RESTORED;
        }
        return RestorationDecision.CONFLICT;
    }
}
