package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.attribute.AttributeModifier;
import org.junit.jupiter.api.Test;

final class MovementAttributePolicyTest {
    private static final double DEFAULT_MOVEMENT_SPEED = 0.1D;

    @Test
    void acceptsDefaultMovementAndTheExactVanillaSprintShape() {
        MovementAttributePolicy.ModifierState sprint = modifier(
                "minecraft:sprinting",
                0.30000001192092896D,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);

        assertAll(
                () -> assertTrue(MovementAttributePolicy.isEligible(
                        DEFAULT_MOVEMENT_SPEED,
                        DEFAULT_MOVEMENT_SPEED,
                        List.of(),
                        false)),
                () -> assertTrue(MovementAttributePolicy.isEligible(
                        DEFAULT_MOVEMENT_SPEED,
                        DEFAULT_MOVEMENT_SPEED,
                        List.of(sprint),
                        true)));
    }

    @Test
    void rejectsAlteredOrNonfiniteBaseValues() {
        assertAll(
                () -> assertFalse(MovementAttributePolicy.isEligible(
                        0.2D, DEFAULT_MOVEMENT_SPEED, List.of(), false)),
                () -> assertFalse(MovementAttributePolicy.isEligible(
                        Double.NaN, DEFAULT_MOVEMENT_SPEED, List.of(), false)),
                () -> assertFalse(MovementAttributePolicy.isEligible(
                        DEFAULT_MOVEMENT_SPEED, Double.POSITIVE_INFINITY, List.of(), false)));
    }

    @Test
    void requiresThePlayerSpecificDefaultRatherThanTheGlobalAttributeDefault() {
        double globalMovementSpeedDefault = 0.7D;

        assertAll(
                () -> assertTrue(MovementAttributePolicy.isEligible(
                        DEFAULT_MOVEMENT_SPEED,
                        DEFAULT_MOVEMENT_SPEED,
                        List.of(),
                        false)),
                () -> assertFalse(MovementAttributePolicy.isEligible(
                        DEFAULT_MOVEMENT_SPEED,
                        globalMovementSpeedDefault,
                        List.of(),
                        false)));
    }

    @Test
    void rejectsCustomEffectAndMalformedSprintModifiers() {
        MovementAttributePolicy.ModifierState customItem = modifier(
                "walktheplank-test:speed_boots",
                0.2D,
                AttributeModifier.Operation.ADD_SCALAR);
        MovementAttributePolicy.ModifierState effect = modifier(
                "minecraft:effect.speed",
                0.2D,
                AttributeModifier.Operation.ADD_SCALAR);
        MovementAttributePolicy.ModifierState sprint = modifier(
                "minecraft:sprinting",
                0.30000001192092896D,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        MovementAttributePolicy.ModifierState wrongSprintAmount = modifier(
                "minecraft:sprinting",
                1.0D,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        MovementAttributePolicy.ModifierState wrongSprintOperation = modifier(
                "minecraft:sprinting",
                0.3D,
                AttributeModifier.Operation.ADD_SCALAR);

        assertAll(
                () -> assertFalse(eligible(customItem, false)),
                () -> assertFalse(eligible(effect, false)),
                () -> assertFalse(eligible(sprint, false)),
                () -> assertFalse(eligible(wrongSprintAmount, true)),
                () -> assertFalse(eligible(wrongSprintOperation, true)),
                () -> assertFalse(MovementAttributePolicy.isEligible(
                        DEFAULT_MOVEMENT_SPEED,
                        DEFAULT_MOVEMENT_SPEED,
                        List.of(sprint, sprint),
                        true)));
    }

    private static boolean eligible(
            MovementAttributePolicy.ModifierState modifier,
            boolean sprinting) {
        return MovementAttributePolicy.isEligible(
                DEFAULT_MOVEMENT_SPEED,
                DEFAULT_MOVEMENT_SPEED,
                List.of(modifier),
                sprinting);
    }

    private static MovementAttributePolicy.ModifierState modifier(
            String key,
            double amount,
            AttributeModifier.Operation operation) {
        return new MovementAttributePolicy.ModifierState(key, operation, amount);
    }
}
