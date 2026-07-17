package com.mrfdev.walktheplank.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DatabaseDoctorReportTest {
    @Test
    void acceptsPrivacySafeCountsAndSizes() {
        assertDoesNotThrow(() -> new DatabaseDoctorReport(true, 8_192L, 4_096L, 2, 7L));
    }

    @Test
    void rejectsNegativeStorageMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DatabaseDoctorReport(true, -1L, 0L, 0, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DatabaseDoctorReport(true, 1L, -1L, 0, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DatabaseDoctorReport(true, 1L, 0L, -1, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DatabaseDoctorReport(true, 1L, 0L, 0, -1L));
    }
}
