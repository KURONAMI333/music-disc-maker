package com.kuronami.musicdiscmaker.speaker;

/** Pure eight-bearing placement decision, kept free of Minecraft classes for regression tests. */
public final class SpeakerPlacement {
    public enum Facing { NORTH, EAST, SOUTH, WEST }
    public enum Turn { CENTER, LEFT, RIGHT }
    public record Orientation(Facing facing, Turn turn) {}
    private SpeakerPlacement() {}

    public static Orientation floorOrCeiling(float playerYaw) {
        if (!Float.isFinite(playerYaw)) return new Orientation(Facing.NORTH, Turn.CENTER);
        return switch (Math.floorMod(Math.round((playerYaw + 180.0F) / 45.0F), 8)) {
            case 0 -> new Orientation(Facing.SOUTH, Turn.CENTER);
            case 1 -> new Orientation(Facing.SOUTH, Turn.RIGHT);
            case 2 -> new Orientation(Facing.WEST, Turn.CENTER);
            case 3 -> new Orientation(Facing.NORTH, Turn.LEFT);
            case 4 -> new Orientation(Facing.NORTH, Turn.CENTER);
            case 5 -> new Orientation(Facing.NORTH, Turn.RIGHT);
            case 6 -> new Orientation(Facing.EAST, Turn.CENTER);
            default -> new Orientation(Facing.SOUTH, Turn.LEFT);
        };
    }

    public static Turn wallTurn(Facing supportBearing, float playerYaw) {
        if (!Float.isFinite(playerYaw)) return Turn.CENTER;
        final float desired = normalize(playerYaw + 180.0F);
        Turn selected = Turn.CENTER;
        float best = difference(desired, bearingYaw(supportBearing));
        for (Turn turn : new Turn[] {Turn.LEFT, Turn.RIGHT}) {
            final float candidate = normalize(bearingYaw(supportBearing) + (turn == Turn.LEFT ? -45.0F : 45.0F));
            final float candidateDifference = difference(desired, candidate);
            if (candidateDifference < best) { selected = turn; best = candidateDifference; }
        }
        return selected;
    }

    private static float bearingYaw(Facing facing) { return switch (facing) {
        case SOUTH -> 0.0F; case WEST -> 90.0F; case NORTH -> 180.0F; case EAST -> 270.0F;
    }; }
    private static float normalize(float value) { float v = value % 360.0F; return v < 0.0F ? v + 360.0F : v; }
    private static float difference(float left, float right) { float d = Math.abs(normalize(left) - normalize(right)); return Math.min(d, 360.0F - d); }
}
