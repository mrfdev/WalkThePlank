package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RewardCommandRootPolicyTest {
    @Test
    void normalizesSafeUniqueRoots() {
        assertEquals(
                Set.of("say", "cmi", "provider:reward"),
                RewardCommandRootPolicy.parse(List.of(
                        " SAY ", "cmi", "provider:reward")));
    }

    @Test
    void rejectsMissingOversizedDuplicateAndUnsafeLists() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RewardCommandRootPolicy.parse("say"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RewardCommandRootPolicy.parse(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> RewardCommandRootPolicy.parse(List.of("say", "SAY")));
        assertThrows(
                IllegalArgumentException.class,
                () -> RewardCommandRootPolicy.parse(List.of("say;op")));
        assertThrows(
                IllegalArgumentException.class,
                () -> RewardCommandRootPolicy.parse(java.util.Collections.nCopies(33, "say")));
    }

    @Test
    void extractsOneCanonicalRootWithoutArguments() {
        assertEquals("cmi", RewardCommandRootPolicy.rootOf("  /cmi msg Alice hello".strip()));
    }
}
