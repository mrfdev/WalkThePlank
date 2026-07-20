package com.mrfdev.walktheplank.game;

import java.util.Collection;
import java.util.Objects;
import org.bukkit.attribute.AttributeModifier;

/** Pure admission rule for movement-speed attribute state used by a parkour runner. */
final class MovementAttributePolicy {
    private static final String SPRINTING_KEY = "minecraft:sprinting";
    private static final AttributeModifier.Operation SPRINT_OPERATION =
            AttributeModifier.Operation.MULTIPLY_SCALAR_1;
    private static final double MINIMUM_SPRINT_AMOUNT = 0.299D;
    private static final double MAXIMUM_SPRINT_AMOUNT = 0.301D;

    private MovementAttributePolicy() {
    }

    static boolean isEligible(
            double baseValue,
            double defaultValue,
            Collection<ModifierState> modifiers,
            boolean sprinting) {
        Objects.requireNonNull(modifiers, "modifiers");
        if (!Double.isFinite(baseValue)
                || !Double.isFinite(defaultValue)
                || Double.compare(baseValue, defaultValue) != 0) {
            return false;
        }

        boolean foundSprintingModifier = false;
        for (ModifierState modifier : modifiers) {
            if (modifier == null || !isVanillaSprintingModifier(modifier, sprinting)) {
                return false;
            }
            if (foundSprintingModifier) {
                return false;
            }
            foundSprintingModifier = true;
        }
        return true;
    }

    private static boolean isVanillaSprintingModifier(
            ModifierState modifier,
            boolean sprinting) {
        double amount = modifier.amount();
        return sprinting
                && SPRINTING_KEY.equals(modifier.key())
                && SPRINT_OPERATION == modifier.operation()
                && Double.isFinite(amount)
                && amount >= MINIMUM_SPRINT_AMOUNT
                && amount <= MAXIMUM_SPRINT_AMOUNT;
    }

    record ModifierState(
            String key,
            AttributeModifier.Operation operation,
            double amount) {
        ModifierState {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
