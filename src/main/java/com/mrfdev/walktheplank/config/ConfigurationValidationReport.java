package com.mrfdev.walktheplank.config;

import java.util.List;
import java.util.Objects;

/** Immutable, safe-to-display result of validating configuration without applying it. */
public record ConfigurationValidationReport(
        List<String> errors,
        List<String> warnings,
        String fingerprint) {
    public ConfigurationValidationReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
