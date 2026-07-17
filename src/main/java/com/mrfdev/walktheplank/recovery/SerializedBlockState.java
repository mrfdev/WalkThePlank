package com.mrfdev.walktheplank.recovery;

import java.util.Objects;

/** Stable material and exact block-data identity used for conflict-safe recovery decisions. */
public record SerializedBlockState(String material, String blockData) {
    private static final int MAX_MATERIAL_LENGTH = 128;
    private static final int MAX_BLOCK_DATA_LENGTH = 8_192;

    public SerializedBlockState {
        material = requireValue(material, "material", MAX_MATERIAL_LENGTH);
        blockData = requireValue(blockData, "blockData", MAX_BLOCK_DATA_LENGTH);
    }

    private static String requireValue(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return normalized;
    }
}
