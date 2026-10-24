package com.saptarishi.backend.pathfinder;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns a blast radius, a file's complexity and its churn into a label a newcomer can act on. */
public final class Difficulty {

    public enum Level { SMALL, MEDIUM, LARGE }

    private static final int COMPLEX = 40;
    private static final int MIN_FILES_WITH_CHURN = 10;
    private static final int MIN_COMMITS_TO_BE_HOT = 3;

    private Difficulty() {}

    /** Relative to repo size, so 5 dependents means different things in a 50-file and a 5,000-file repo. */
    public static Level blast(int dependents, int totalFiles) {
        double ratio = totalFiles == 0 ? 0 : (double) dependents / totalFiles;
        if (dependents <= 2 || ratio < 0.03) return Level.SMALL;
        return ratio < 0.12 ? Level.MEDIUM : Level.LARGE;
    }

    /** EASY, MODERATE or RISKY, ignoring churn. */
    public static String rate(Level blast, int complexity) {
        return rate(blast, complexity, false);
    }

    /**
     * A file that is changed unusually often is riskier for a first change: other people are
     * editing it, so conflicts and surprises are more likely. It counts like a complex file.
     */
    public static String rate(Level blast, int complexity, boolean hot) {
        boolean complex = complexity >= COMPLEX;
        if (blast == Level.LARGE || ((complex || hot) && blast == Level.MEDIUM)) return "RISKY";
        if (blast == Level.SMALL && !complex && !hot) return "EASY";
        return "MODERATE";
    }

    /**
     * Files in roughly the top 10% by commit count (and touched at least 3 times). Empty when churn
     * was never computed, or too few files changed to say what "often" means.
     */
    public static Set<String> hotFiles(Map<String, Integer> churn) {
        if (churn.size() < MIN_FILES_WITH_CHURN) return Set.of();
        int[] sorted = churn.values().stream().mapToInt(Integer::intValue).sorted().toArray();
        int threshold = Math.max(MIN_COMMITS_TO_BE_HOT, sorted[(int) Math.ceil(0.9 * (sorted.length - 1))]);
        return churn.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
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