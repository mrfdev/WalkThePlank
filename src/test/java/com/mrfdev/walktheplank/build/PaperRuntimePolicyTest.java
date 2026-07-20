package com.mrfdev.walktheplank.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class PaperRuntimePolicyTest {
    @Test
    void acceptsBuild62AndNewerOnPaper262() {
        PaperRuntimePolicy.Verification exact =
                verify("26.2", OptionalInt.of(62));
        PaperRuntimePolicy.Verification newer =
                verify("26.2", OptionalInt.of(70));

        assertTrue(exact.supported());
        assertTrue(newer.supported());
        assertEquals("Paper 26.2 build 62 or newer", exact.targetLabel());
        assertEquals("Paper 26.2 build 62", exact.runtimeLabel());
    }

    @Test
    void rejectsOlderBuildsAndEveryOlderMinecraftLine() {
        PaperRuntimePolicy.Verification oldBuild =
                verify("26.2", OptionalInt.of(61));
        PaperRuntimePolicy.Verification oldMinecraft =
                verify("26.1.2", OptionalInt.of(999));

        assertFalse(oldBuild.supported());
        assertEquals(
                PaperRuntimePolicy.Decision.BUILD_TOO_OLD,
                oldBuild.decision());
        assertFalse(oldMinecraft.supported());
        assertEquals(
                PaperRuntimePolicy.Decision.MINECRAFT_VERSION_MISMATCH,
                oldMinecraft.decision());
    }

    @Test
    void failsClosedWhenThePaperBuildNumberIsUnavailable() {
        PaperRuntimePolicy.Verification unknown =
                verify("26.2", OptionalInt.empty());

        assertFalse(unknown.supported());
        assertEquals(
                PaperRuntimePolicy.Decision.BUILD_NUMBER_UNAVAILABLE,
                unknown.decision());
        assertTrue(unknown.failureMessage().contains("build unknown"));
    }

    private static PaperRuntimePolicy.Verification verify(
            String runtimeMinecraft,
            OptionalInt runtimeBuild) {
        return PaperRuntimePolicy.evaluate(
                "26.2",
                62,
                "Paper",
                runtimeMinecraft,
                runtimeBuild);
    }
}
