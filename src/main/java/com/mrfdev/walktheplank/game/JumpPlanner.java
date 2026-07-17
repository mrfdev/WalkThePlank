package com.mrfdev.walktheplank.game;

import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.function.Predicate;

public final class JumpPlanner {
    private static final int MAX_ATTEMPTS = 48;
    static final int VERTICAL_RADIUS = 6;

    private final int horizontalRadius;

    public JumpPlanner(int horizontalRadius) {
        if (horizontalRadius < 3) {
            throw new IllegalArgumentException("horizontalRadius must be at least 3");
        }
        this.horizontalRadius = horizontalRadius;
    }

    public GridPoint next(
            GridPoint center,
            GridPoint current,
            int score,
            RandomGenerator random,
            Predicate<GridPoint> isUsable) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(isUsable, "isUsable");

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int vertical = chooseVertical(center, current, random);
            int distance = chooseDistance(vertical, score, random);
            int direction = random.nextInt(4);
            int deltaX = direction == 0 ? distance : direction == 1 ? -distance : 0;
            int deltaZ = direction == 2 ? distance : direction == 3 ? -distance : 0;
            GridPoint candidate = current.add(deltaX, vertical, deltaZ);

            if (insideBounds(center, candidate) && isUsable.test(candidate)) {
                return candidate;
            }
        }

        for (int distance = 2; distance <= 4; distance++) {
            int towardX = Integer.compare(center.x(), current.x()) * distance;
            int towardZ = Integer.compare(center.z(), current.z()) * distance;
            GridPoint[] fallbacks = {
                current.add(towardX, 0, 0),
                current.add(0, 0, towardZ),
                current.add(distance, 0, 0),
                current.add(-distance, 0, 0),
                current.add(0, 0, distance),
                current.add(0, 0, -distance)
            };
            for (GridPoint candidate : fallbacks) {
                if (!candidate.equals(current)
                        && insideBounds(center, candidate)
                        && isUsable.test(candidate)) {
                    return candidate;
                }
            }
        }

        throw new IllegalStateException("Could not find a safe location for the next parkour block");
    }

    private int chooseVertical(GridPoint center, GridPoint current, RandomGenerator random) {
        int relativeY = current.y() - center.y();
        if (relativeY <= -VERTICAL_RADIUS + 1) {
            return random.nextBoolean() ? 0 : 1;
        }
        if (relativeY >= VERTICAL_RADIUS - 1) {
            return random.nextBoolean() ? 0 : -1;
        }
        return random.nextInt(-1, 2);
    }

    private int chooseDistance(int vertical, int score, RandomGenerator random) {
        if (vertical > 0) {
            if (score < 20) {
                return random.nextInt(2, 4);
            }
            if (score < 50) {
                return random.nextInt(2, 5);
            }
            return random.nextInt(3, 5);
        }
        if (vertical < 0) {
            if (score < 20) {
                return random.nextInt(2, 5);
            }
            if (score < 50) {
                return random.nextInt(3, 5);
            }
            return random.nextInt(3, 6);
        }
        return score < 100 ? random.nextInt(2, 5) : random.nextInt(3, 6);
    }

    private boolean insideBounds(GridPoint center, GridPoint candidate) {
        return Math.abs(candidate.x() - center.x()) <= horizontalRadius
                && Math.abs(candidate.z() - center.z()) <= horizontalRadius
                && Math.abs(candidate.y() - center.y()) <= VERTICAL_RADIUS;
    }
}
