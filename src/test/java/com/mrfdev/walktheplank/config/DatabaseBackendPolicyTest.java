package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseBackendPolicyTest {
    @Test
    void acceptsExplicitAndImplicitSQLite() {
        assertDoesNotThrow(() -> DatabaseBackendPolicy.requireSQLite(null, false));
        assertDoesNotThrow(() -> DatabaseBackendPolicy.requireSQLite("SQLITE", false));
        assertDoesNotThrow(() -> DatabaseBackendPolicy.requireSQLite("sqlite", false));
    }

    @Test
    void rejectsLegacyAndExplicitMySqlWithoutFallingBack() {
        IllegalArgumentException legacy = assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseBackendPolicy.requireSQLite("SQLITE", true));
        IllegalArgumentException explicit = assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseBackendPolicy.requireSQLite("MYSQL", false));

        assertTrue(legacy.getMessage().contains("migrate scores"));
        assertTrue(explicit.getMessage().contains("not supported"));
    }

    @Test
    void rejectsUnknownBackend() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseBackendPolicy.requireSQLite("POSTGRESQL", false));

        assertTrue(failure.getMessage().contains("must be SQLITE"));
    }
}
