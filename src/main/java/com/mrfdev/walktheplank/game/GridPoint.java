package com.mrfdev.walktheplank.game;

public record GridPoint(int x, int y, int z) {
    public GridPoint add(int deltaX, int deltaY, int deltaZ) {
        return new GridPoint(x + deltaX, y + deltaY, z + deltaZ);
    }
}
