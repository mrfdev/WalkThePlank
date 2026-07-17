package com.mrfdev.walktheplank.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Stateful, main-thread arena selection with deterministic fairness policies. */
public final class ArenaSelector {
    private final RandomGenerator random;
    private final Map<String, Long> lastUsedSequence = new HashMap<>();
    private long sequence;
    private String lastRoundRobinArenaId;

    public ArenaSelector(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public String select(
            List<String> availableArenaIds,
            ArenaSelectionPolicy policy,
            String pinnedArenaId) {
        List<String> available = List.copyOf(availableArenaIds);
        if (available.isEmpty()) {
            throw new IllegalArgumentException("At least one available arena is required");
        }
        if (available.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("Arena IDs must be nonblank");
        }
        if (new HashSet<>(available).size() != available.size()) {
            throw new IllegalArgumentException("Available arena IDs must be unique");
        }
        ArenaSelectionPolicy checkedPolicy = Objects.requireNonNull(policy, "policy");

        String selected = switch (checkedPolicy) {
            case RANDOM -> available.get(random.nextInt(available.size()));
            case ROUND_ROBIN -> selectRoundRobin(available);
            case LEAST_RECENTLY_USED -> selectLeastRecentlyUsed(available);
            case PINNED -> available.contains(pinnedArenaId)
                    ? pinnedArenaId
                    : selectLeastRecentlyUsed(available);
        };
        lastUsedSequence.put(selected, ++sequence);
        return selected;
    }

    public void forget(String arenaId) {
        lastUsedSequence.remove(Objects.requireNonNull(arenaId, "arenaId"));
    }

    private String selectRoundRobin(List<String> available) {
        List<String> ordered = available.stream().sorted().toList();
        String selected = ordered.getFirst();
        if (lastRoundRobinArenaId != null) {
            selected = ordered.stream()
                    .filter(candidate -> candidate.compareTo(lastRoundRobinArenaId) > 0)
                    .findFirst()
                    .orElse(selected);
        }
        lastRoundRobinArenaId = selected;
        return selected;
    }

    private String selectLeastRecentlyUsed(List<String> available) {
        String selected = available.getFirst();
        long oldest = lastUsedSequence.getOrDefault(selected, Long.MIN_VALUE);
        for (int index = 1; index < available.size(); index++) {
            String candidate = available.get(index);
            long used = lastUsedSequence.getOrDefault(candidate, Long.MIN_VALUE);
            if (used < oldest) {
                selected = candidate;
                oldest = used;
            }
        }
        return selected;
    }
}
