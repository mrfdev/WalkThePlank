package com.mrfdev.walktheplank.gui;

enum LeaderboardKind {
    CLASSIC("Classic"),
    SEASON("Season"),
    COMBO("Combo"),
    FLAWLESS("Flawless");

    private final String label;

    LeaderboardKind(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
