package com.mrfdev.walktheplank.database;

/** Indicates that a score database operation could not be completed safely. */
public final class ScoreRepositoryException extends Exception {
    private static final long serialVersionUID = 1L;

    public ScoreRepositoryException(String message) {
        super(message);
    }

    public ScoreRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
