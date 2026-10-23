package com.saptarishi.backend.pathfinder;

/** Turns a blast radius and a file's complexity into a label a newcomer can act on. */
public final class Difficulty {

    public enum Level { SMALL, MEDIUM, LARGE }

    private static final int COMPLEX = 40;

    private Difficulty() {}

    /** Relative to repo size, so 5 dependents means different things in a 50-file and a 5,000-file repo. */
    public static Level blast(int dependents, int totalFiles) {
        double ratio = totalFiles == 0 ? 0 : (double) dependents / totalFiles;
        if (dependents <= 2 || ratio < 0.03) return Level.SMALL;
        return ratio < 0.12 ? Level.MEDIUM : Level.LARGE;
    }

    /** EASY, MODERATE or RISKY. */
    public static String rate(Level blast, int complexity) {
        boolean complex = complexity >= COMPLEX;
        if (blast == Level.LARGE || (complex && blast == Level.MEDIUM)) return "RISKY";
        if (blast == Level.SMALL && !complex) return "EASY";
        return "MODERATE";
    }

    public static int order(String difficulty) {
        return switch (difficulty) {
            case "EASY" -> 0;
            case "MODERATE" -> 1;
            case "RISKY" -> 2;
            default -> 3;
        };
    }
}