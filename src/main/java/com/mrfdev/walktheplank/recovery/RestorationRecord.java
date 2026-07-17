package com.mrfdev.walktheplank.recovery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** One durable write-ahead record for a single plugin-owned arena block mutation. */
public record RestorationRecord(
        UUID journalId,
        UUID sessionId,
        String arenaId,
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        SerializedBlockState expectedState,
        SerializedBlockState originalState,
        String originalStructureBase64,
        String originalFingerprint,
        String releaseIdentity,
        long createdAtEpochMillis) {
    private static final int SHA_256_HEX_LENGTH = 64;
    private static final int MAX_ARENA_ID_LENGTH = 256;
    private static final int MAX_WORLD_NAME_LENGTH = 256;
    private static final int MAX_RELEASE_IDENTITY_LENGTH = 1_024;
    private static final int MAX_STRUCTURE_BASE64_LENGTH = 22_369_624;

    public RestorationRecord {
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(sessionId, "sessionId");
        arenaId = requireValue(arenaId, "arenaId", MAX_ARENA_ID_LENGTH);
        Objects.requireNonNull(worldId, "worldId");
        worldName = requireValue(worldName, "worldName", MAX_WORLD_NAME_LENGTH);
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(originalState, "originalState");
        originalStructureBase64 = requireValue(
                originalStructureBase64,
                "originalStructureBase64",
                MAX_STRUCTURE_BASE64_LENGTH);
        originalFingerprint = requireValue(
                originalFingerprint,
                "originalFingerprint",
                SHA_256_HEX_LENGTH).toLowerCase(Locale.ROOT);
        releaseIdentity = requireValue(
                releaseIdentity,
                "releaseIdentity",
                MAX_RELEASE_IDENTITY_LENGTH);
        if (createdAtEpochMillis < 0L) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
        if (originalFingerprint.length() != SHA_256_HEX_LENGTH
                || !originalFingerprint.codePoints().allMatch(RestorationRecord::isLowerHexDigit)) {
            throw new IllegalArgumentException("originalFingerprint must be a SHA-256 hex value");
        }
        byte[] snapshot = decodeSnapshot(originalStructureBase64);
        if (snapshot.length == 0) {
            throw new IllegalArgumentException("original structure snapshot must not be empty");
        }
        if (!originalFingerprint.equals(fingerprint(snapshot))) {
            throw new IllegalArgumentException("original structure snapshot fingerprint does not match");
        }
    }

    static RestorationRecord create(
            UUID journalId,
            UUID sessionId,
            String arenaId,
            UUID worldId,
            String worldName,
            int x,
            int y,
            int z,
            SerializedBlockState expectedState,
            SerializedBlockState originalState,
            byte[] originalStructure,
            String releaseIdentity,
            long createdAtEpochMillis) {
        Objects.requireNonNull(originalStructure, "originalStructure");
        return new RestorationRecord(
                journalId,
                sessionId,
                arenaId,
                worldId,
                worldName,
                x,
                y,
                z,
                expectedState,
                originalState,
                Base64.getEncoder().encodeToString(originalStructure),
                fingerprint(originalStructure),
                releaseIdentity,
                createdAtEpochMillis);
    }

    public byte[] originalStructure() {
        return decodeSnapshot(originalStructureBase64);
    }

    public boolean isSameBlock(RestorationRecord other) {
        Objects.requireNonNull(other, "other");
        return worldId.equals(other.worldId) && x == other.x && y == other.y && z == other.z;
    }

    static String fingerprint(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    private static byte[] decodeSnapshot(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("originalStructureBase64 is not valid Base64", exception);
        }
    }

    private static boolean isLowerHexDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9' || codePoint >= 'a' && codePoint <= 'f';
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
        if (!"originalStructureBase64".equals(name)
                && normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return normalized;
    }
}
