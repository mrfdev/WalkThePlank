package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.recovery.RestorationCoordinator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

final class GameSession {
    private final Player player;
    private final Arena arena;
    private final PlayerSnapshot playerSnapshot;
    private final RuntimeSettings settings;
    private final ArenaBounds bounds;
    private final RestorationCoordinator restoration;
    private final JumpPlanner jumpPlanner;
    private final UUID runId;
    private final Instant startedAt;
    private final SplittableRandom random = new SplittableRandom();
    private final Predicate<BlockKey> blockAvailable;
    private final Consumer<BlockKey> protectBlock;
    private final Consumer<BlockKey> unprotectBlock;
    private final Deque<PlacedBlock> placedBlocks = new ArrayDeque<>(2);

    private GridPoint currentPoint;
    private PlacedBlock targetBlock;
    private int score;
    private boolean ended;
    private long startedAtNanos;
    private long lastProgressAtNanos;

    GameSession(
            Player player,
            Arena arena,
            UUID runId,
            Instant startedAt,
            RuntimeSettings settings,
            Predicate<BlockKey> blockAvailable,
            Consumer<BlockKey> protectBlock,
            Consumer<BlockKey> unprotectBlock,
            RestorationCoordinator restoration) {
        this.player = Objects.requireNonNull(player, "player");
        this.arena = Objects.requireNonNull(arena, "arena");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.blockAvailable = Objects.requireNonNull(blockAvailable, "blockAvailable");
        this.protectBlock = Objects.requireNonNull(protectBlock, "protectBlock");
        this.unprotectBlock = Objects.requireNonNull(unprotectBlock, "unprotectBlock");
        this.restoration = Objects.requireNonNull(restoration, "restoration");
        playerSnapshot = PlayerSnapshot.capture(player);
        bounds = ArenaBounds.around(arena, settings.horizontalRadius(), settings.fallDistance());
        jumpPlanner = new JumpPlanner(settings.horizontalRadius());
    }

    void start() {
        Location baseLocation = arena.baseBlock();
        if (!ArenaBounds.hasClearHeadroom(baseLocation.getBlock())) {
            throw new IllegalStateException("Arena start no longer has two clear air blocks of headroom");
        }
        startedAtNanos = System.nanoTime();
        lastProgressAtNanos = startedAtNanos;
        currentPoint = point(baseLocation);
        place(baseLocation, false);
        GridPoint next = findNext(currentPoint);
        PlacedBlock target = place(location(next), settings.onlyReplaceAir());
        targetBlock = target;
        playerSnapshot.prepare(player);
    }

    int advance() {
        ensureActive();
        PlacedBlock expectedTarget = targetBlock;
        if (expectedTarget == null
                || !expectedTarget.isIntact()
                || blockAvailable.test(expectedTarget.key())) {
            throw new IllegalStateException("The current parkour target is no longer intact and protected");
        }
        score++;

        PlacedBlock previous = placedBlocks.peekFirst();
        if (previous == null) {
            throw new IllegalStateException("Session lost its previous block");
        }
        previous.restore();
        unprotectBlock.accept(previous.key());
        placedBlocks.removeFirst();

        PlacedBlock landed = placedBlocks.peekFirst();
        if (landed == null) {
            throw new IllegalStateException("Session lost its current block");
        }
        if (landed != expectedTarget) {
            throw new IllegalStateException("Session target does not match the landed block");
        }
        currentPoint = new GridPoint(landed.key().x(), landed.key().y(), landed.key().z());
        GridPoint next = findNext(currentPoint);
        PlacedBlock target = place(location(next), settings.onlyReplaceAir());
        targetBlock = target;
        lastProgressAtNanos = System.nanoTime();
        return score;
    }

    int finish() {
        ended = true;
        if (placedBlocks.isEmpty()) {
            return score;
        }
        RuntimeException failure = null;
        Deque<PlacedBlock> failedRestores = new ArrayDeque<>();
        int pendingBlocks = placedBlocks.size();
        for (int index = 0; index < pendingBlocks; index++) {
            PlacedBlock placed = placedBlocks.removeLast();
            try {
                placed.restore();
                unprotectBlock.accept(placed.key());
            } catch (RuntimeException exception) {
                failedRestores.addFirst(placed);
                failure = appendFailure(failure, exception);
            }
        }
        placedBlocks.addAll(failedRestores);
        if (failure != null) {
            throw failure;
        }
        return score;
    }

    boolean isTarget(BlockKey key) {
        return !ended
                && targetBlock != null
                && key.equals(targetBlock.key())
                && targetBlock.isIntact()
                && !blockAvailable.test(key);
    }

    boolean hasFallen(Location location) {
        return location.getWorld() == arena.start().getWorld()
                && location.getY() < arena.start().getY() - settings.fallDistance();
    }

    boolean contains(Location location) {
        return bounds.contains(location);
    }

    Player player() {
        return player;
    }

    Arena arena() {
        return arena;
    }

    PlayerSnapshot playerSnapshot() {
        return playerSnapshot;
    }

    int score() {
        return score;
    }

    UUID runId() {
        return runId;
    }

    Instant startedAt() {
        return Objects.requireNonNull(startedAt, "Session has not started");
    }

    boolean isExpired(long nowNanos) {
        if (startedAtNanos == 0L || ended) {
            return false;
        }
        return elapsedSeconds(nowNanos) >= settings.maximumRunSeconds()
                || idleSeconds(nowNanos) >= settings.idleTimeoutSeconds();
    }

    long elapsedSeconds(long nowNanos) {
        return elapsedDuration(nowNanos).toSeconds();
    }

    Duration elapsedDuration(long nowNanos) {
        return Duration.ofNanos(Math.max(0L, nowNanos - startedAtNanos));
    }

    long idleSeconds(long nowNanos) {
        return Duration.ofNanos(Math.max(0L, nowNanos - lastProgressAtNanos)).toSeconds();
    }

    private GridPoint findNext(GridPoint current) {
        GridPoint center = point(arena.baseBlock());
        return jumpPlanner.next(center, current, score, random, candidate -> {
            Location location = location(candidate);
            World world = location.getWorld();
            if (world == null
                    || candidate.y() < world.getMinHeight()
                    || candidate.y() + 2 >= world.getMaxHeight()) {
                return false;
            }
            Block block = location.getBlock();
            if (!blockAvailable.test(BlockKey.from(block))) {
                return false;
            }
            if (!ArenaBounds.hasClearHeadroom(block)) {
                return false;
            }
            return !settings.onlyReplaceAir() || block.getType().isAir();
        });
    }

    private PlacedBlock place(Location location, boolean requireAir) {
        Block block = location.getBlock();
        BlockKey key = BlockKey.from(block);
        if (!blockAvailable.test(key)) {
            throw new IllegalStateException("Parkour block location is already in use: " + key);
        }
        if (requireAir && !block.getType().isAir()) {
            throw new IllegalStateException("Parkour block location is not air: " + key);
        }
        List<Material> materials = settings.parkourBlocks();
        Material material = materials.get(random.nextInt(materials.size()));
        PlacedBlock placed = PlacedBlock.prepare(
                block,
                material,
                runId,
                arena.id(),
                restoration);
        placedBlocks.addLast(placed);
        protectBlock.accept(placed.key());
        placed.place();

        if (settings.particlesEnabled() && settings.particleCount() > 0) {
            Location particleLocation = block.getLocation().add(0.5, 1.1, 0.5);
            block.getWorld().spawnParticle(
                    settings.particle(),
                    particleLocation,
                    settings.particleCount(),
                    0.25,
                    0.2,
                    0.25,
                    0.02);
        }
        return placed;
    }

    private Location location(GridPoint point) {
        World world = arena.start().getWorld();
        return new Location(world, point.x(), point.y(), point.z());
    }

    private static GridPoint point(Location location) {
        return new GridPoint(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void ensureActive() {
        if (ended) {
            throw new IllegalStateException("Session has already ended");
        }
    }

    private static RuntimeException appendFailure(
            RuntimeException existing, RuntimeException additional) {
        if (existing == null) {
            return additional;
        }
        existing.addSuppressed(additional);
        return existing;
    }
}
