package com.mrfdev.walktheplank.build;

import java.util.Objects;

/** Classifies the running JVM against the plugin's compiled Java feature target. */
public final class JavaRuntimePolicy {
    private JavaRuntimePolicy() {
    }

    public static Verification current(BuildInfo target) {
        Objects.requireNonNull(target, "target");
        return evaluate(
                Integer.parseInt(target.javaTarget()),
                Runtime.version());
    }

    static Verification evaluate(
            int targetFeature,
            Runtime.Version runtimeVersion) {
        Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        Decision decision;
        if (runtimeVersion.feature() < targetFeature) {
            decision = Decision.TOO_OLD;
        } else if (runtimeVersion.feature() == targetFeature) {
            decision = Decision.EXACT;
        } else {
            decision = Decision.NEWER_COMPATIBLE;
        }
        return new Verification(targetFeature, runtimeVersion, decision);
    }

    public enum Decision {
        EXACT,
        NEWER_COMPATIBLE,
        TOO_OLD
    }

    public record Verification(
            int targetFeature,
            Runtime.Version runtimeVersion,
            Decision decision) {
        public Verification {
            Objects.requireNonNull(runtimeVersion, "runtimeVersion");
            Objects.requireNonNull(decision, "decision");
            if (targetFeature < 1) {
                throw new IllegalArgumentException(
                        "targetFeature must be positive");
            }
        }

        public boolean supported() {
            return decision != Decision.TOO_OLD;
        }

        public String relationshipLabel() {
            return switch (decision) {
                case EXACT -> "match";
                case NEWER_COMPATIBLE -> "compatible newer runtime";
                case TOO_OLD -> "TOO OLD";
            };
        }
    }
}
