package com.mrfdev.walktheplank.game;

import java.util.Objects;
import org.bukkit.Location;

public record Arena(String id, Location start, Location exit) {
    public Arena {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(start, "start");
        if (start.getWorld() == null) {
            throw new IllegalArgumentException("Arena start must have a world");
        }
        start = start.clone();
        exit = exit == null ? null : exit.clone();
    }

    @Override
    public Location start() {
        return start.clone();
    }

    @Override
    public Location exit() {
        return exit == null ? null : exit.clone();
    }

    public Location playerSpawn() {
        return start.clone().add(0.5, 0.5, 0.5);
    }

    public Location baseBlock() {
        return start.clone().subtract(0.0, 1.0, 0.0).toBlockLocation();
    }
}
