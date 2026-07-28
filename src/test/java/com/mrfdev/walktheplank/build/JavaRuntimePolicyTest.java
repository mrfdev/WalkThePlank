package com.mrfdev.walktheplank.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class JavaRuntimePolicyTest {
    @Test
    void acceptsTheExactTargetRuntime() {
        JavaRuntimePolicy.Verification verification =
                verify("25.0.4");

        assertTrue(verification.supported());
        assertEquals(JavaRuntimePolicy.Decision.EXACT, verification.decision());
        assertEquals("match", verification.relationshipLabel());
    }

    @Test
    void acceptsAndIdentifiesANewerRuntime() {
        JavaRuntimePolicy.Verification verification =
                verify("26.0.2");

        assertTrue(verification.supported());
        assertEquals(
                JavaRuntimePolicy.Decision.NEWER_COMPATIBLE,
                verification.decision());
        assertEquals(
                "compatible newer runtime",
                verification.relationshipLabel());
    }

    @Test
    void rejectsAnOlderRuntime() {
        JavaRuntimePolicy.Verification verification =
                verify("24.0.2");

        assertFalse(verification.supported());
        assertEquals(
                JavaRuntimePolicy.Decision.TOO_OLD,
                verification.decision());
        assertEquals("TOO OLD", verification.relationshipLabel());
    }

    private static JavaRuntimePolicy.Verification verify(String version) {
        return JavaRuntimePolicy.evaluate(
                25,
                Runtime.Version.parse(version));
    }
}
