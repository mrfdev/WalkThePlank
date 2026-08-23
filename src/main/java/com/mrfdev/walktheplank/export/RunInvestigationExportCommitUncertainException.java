package com.mrfdev.walktheplank.export;

import java.io.IOException;
import java.util.Objects;

/** Atomic publication succeeded, but cleanup or directory durability could not be confirmed. */
public final class RunInvestigationExportCommitUncertainException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String fileName;
    private final String sha256;

    public RunInvestigationExportCommitUncertainException(
            String fileName,
            String sha256,
            Throwable cause) {
        super("Investigation export commit is uncertain", cause);
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        if (fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("fileName must be a basename");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase digest");
        }
    }

    public String fileName() {
        return fileName;
    }

    public String sha256() {
        return sha256;
    }
}
