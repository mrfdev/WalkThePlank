package com.mrfdev.walktheplank.build;

import io.papermc.paper.ServerBuildInfo;
import java.util.Objects;
import java.util.OptionalInt;

/** Paper-compatible release-line and explicit minimum-build startup policy. */
public final class PaperRuntimePolicy {
    private PaperRuntimePolicy() {
    }

    public static Verification current(BuildInfo target) {
        Objects.requireNonNull(target, "target");
        ServerBuildInfo runtime = ServerBuildInfo.buildInfo();
        return evaluate(
                target.paperTarget(),
                target.paperMinimumBuild(),
                runtime.brandName(),
                runtime.minecraftVersionId(),
                runtime.buildNumber());
    }

    public static Verification requireSupported(BuildInfo target) {
        Verification verification = current(target);
        if (!verification.supported()) {
            throw new IllegalStateException(verification.failureMessage());
        }
        return verification;
    }

    static Verification evaluate(
            String targetMinecraft,
            int minimumBuild,
            String runtimeBrand,
            String runtimeMinecraft,
            OptionalInt runtimeBuild) {
        Objects.requireNonNull(targetMinecraft, "targetMinecraft");
        Objects.requireNonNull(runtimeBrand, "runtimeBrand");
        Objects.requireNonNull(runtimeMinecraft, "runtimeMinecraft");
        Objects.requireNonNull(runtimeBuild, "runtimeBuild");
        Decision decision;
        if (!targetMinecraft.equals(runtimeMinecraft)) {
            decision = Decision.MINECRAFT_VERSION_MISMATCH;
        } else if (runtimeBuild.isEmpty()) {
            decision = Decision.BUILD_NUMBER_UNAVAILABLE;
        } else if (runtimeBuild.orElseThrow() < minimumBuild) {
            decision = Decision.BUILD_TOO_OLD;
        } else {
            decision = Decision.SUPPORTED;
        }
        return new Verification(
                targetMinecraft,
                minimumBuild,
                runtimeBrand.strip(),
                runtimeMinecraft.strip(),
                runtimeBuild,
                decision);
    }

    public enum Decision {
        SUPPORTED,
        MINECRAFT_VERSION_MISMATCH,
        BUILD_NUMBER_UNAVAILABLE,
        BUILD_TOO_OLD
    }

    public record Verification(
            String targetMinecraft,
            int minimumBuild,
            String runtimeBrand,
            String runtimeMinecraft,
            OptionalInt runtimeBuild,
            Decision decision) {
        public Verification {
            Objects.requireNonNull(targetMinecraft, "targetMinecraft");
            Objects.requireNonNull(runtimeBrand, "runtimeBrand");
            Objects.requireNonNull(runtimeMinecraft, "runtimeMinecraft");
            Objects.requireNonNull(runtimeBuild, "runtimeBuild");
            Objects.requireNonNull(decision, "decision");
            if (minimumBuild < 1) {
                throw new IllegalArgumentException("minimumBuild must be positive");
            }
        }

        public boolean supported() {
            return decision == Decision.SUPPORTED;
        }

        public String targetLabel() {
            return "Paper " + targetMinecraft + " build " + minimumBuild + " or newer";
        }

        public String runtimeLabel() {
            return runtimeBrand + " " + runtimeMinecraft + " build "
                    + (runtimeBuild.isPresent()
                            ? runtimeBuild.orElseThrow()
                            : "unknown");
        }

        public String failureMessage() {
            return "WalkThePlank requires " + targetLabel()
                    + "; detected " + runtimeLabel()
                    + " (" + decision.name() + ")";
        }
    }
}
