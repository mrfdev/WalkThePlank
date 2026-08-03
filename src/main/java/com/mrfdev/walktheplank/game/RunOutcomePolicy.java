package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.database.RunCategoryScore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure terminal projection that keeps administrative test runs out of scoring persistence. */
final class RunOutcomePolicy {
    private RunOutcomePolicy() {
    }

    static Projection project(
            boolean adminTest,
            SessionEndReason reason,
            int observedScore,
            List<RunCategoryScore> observedCategories) {
        Objects.requireNonNull(reason, "reason");
        List<RunCategoryScore> checkedCategories = List.copyOf(
                Objects.requireNonNull(observedCategories, "observedCategories"));
        if (observedScore < 0) {
            throw new IllegalArgumentException("observedScore must not be negative");
        }
        if (adminTest) {
            return new Projection(
                    0,
                    List.of(),
                    false,
                    false,
                    Optional.of("ADMIN_TEST_" + reason.name()));
        }
        boolean scoringEligible = reason != SessionEndReason.MOVEMENT_MODIFIED;
        return new Projection(
                scoringEligible ? observedScore : 0,
                scoringEligible ? checkedCategories : List.of(),
                scoringEligible,
                true,
                Optional.empty());
    }

    record Projection(
            int authoritativeScore,
            List<RunCategoryScore> categoryScores,
            boolean scoringEligible,
            boolean publicEventsEligible,
            Optional<String> interruptedReason) {
        Projection {
            if (authoritativeScore < 0) {
                throw new IllegalArgumentException("authoritativeScore must not be negative");
            }
            categoryScores = List.copyOf(Objects.requireNonNull(categoryScores, "categoryScores"));
            interruptedReason = Objects.requireNonNull(interruptedReason, "interruptedReason");
            if (interruptedReason.isPresent()
                    && (authoritativeScore != 0
                            || !categoryScores.isEmpty()
                            || scoringEligible
                            || publicEventsEligible)) {
                throw new IllegalArgumentException(
                        "interrupted test projections cannot expose scoring state");
            }
        }
    }
}
