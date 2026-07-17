package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class PersistenceValidation {
    private static final Pattern PLAYER_NAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_]{3,16}");

    private PersistenceValidation() {
    }

    static String playerName(String value) {
        Objects.requireNonNull(value, "username");
        if (!PLAYER_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "username must contain 3 to 16 ASCII letters, digits, or underscores");
        }
        return value;
    }

    static String text(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must not exceed " + maximumLength + " characters");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return value;
    }

    static Instant instant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        return Instant.ofEpochMilli(value.toEpochMilli());
    }

    static Optional<Instant> optionalInstant(Optional<Instant> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(instant -> instant(instant, name));
    }

    static String commandRoot(String value) {
        String root = text(value, "commandRoot", 128).toLowerCase(Locale.ROOT);
        if (root.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("commandRoot must not contain whitespace");
        }
        return root;
    }

    static String commandHash(String value) {
        Objects.requireNonNull(value, "commandHash");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("commandHash must be a lowercase SHA-256 hex digest");
        }
        return normalized;
    }
}
