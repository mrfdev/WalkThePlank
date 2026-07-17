package com.mrfdev.walktheplank.recovery;

public record RestorationRetryResult(
        int attemptedRecords,
        int restoredRecords,
        int alreadyRestoredRecords,
        int conflictRecords,
        int missingWorldRecords,
        int failedRecords,
        int recoveredArenas,
        int pendingRecords,
        int conflictedRecords) {
    public RestorationRetryResult {
        if (attemptedRecords < 0
                || restoredRecords < 0
                || alreadyRestoredRecords < 0
                || conflictRecords < 0
                || missingWorldRecords < 0
                || failedRecords < 0
                || recoveredArenas < 0
                || pendingRecords < 0
                || conflictedRecords < 0) {
            throw new IllegalArgumentException("Restoration retry counts must not be negative");
        }
    }

    public int completedRecords() {
        return restoredRecords + alreadyRestoredRecords;
    }

    public static RestorationRetryResult empty(int pendingRecords, int conflictedRecords) {
        return new RestorationRetryResult(
                0, 0, 0, 0, 0, 0, 0, pendingRecords, conflictedRecords);
    }
}
