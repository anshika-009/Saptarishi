package com.saptarishi.backend.pathfinder;

import static com.saptarishi.backend.pathfinder.Difficulty.Level.LARGE;
import static com.saptarishi.backend.pathfinder.Difficulty.Level.MEDIUM;
import static com.saptarishi.backend.pathfinder.Difficulty.Level.SMALL;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DifficultyTest {

    @Test
    void blastLevelIsRelativeToRepoSize() {
        assertEquals(SMALL, Difficulty.blast(2, 10));      // tiny absolute count always small
        assertEquals(SMALL, Difficulty.blast(2, 0));
        assertEquals(SMALL, Difficulty.blast(20, 1000));   // 2% of a big repo
        assertEquals(MEDIUM, Difficulty.blast(5, 100));
        assertEquals(LARGE, Difficulty.blast(20, 100));
    }

    @Test
    void ratingCombinesBlastAndComplexity() {
        assertEquals("EASY", Difficulty.rate(SMALL, 10));
        assertEquals("MODERATE", Difficulty.rate(SMALL, 50));
        assertEquals("MODERATE", Difficulty.rate(MEDIUM, 10));
        assertEquals("RISKY", Difficulty.rate(MEDIUM, 50));
        assertEquals("RISKY", Difficulty.rate(LARGE, 1));
    }

    @Test
    void orderingPutsEasyFirstAndUnknownLast() {
        assertTrue(Difficulty.order("EASY") < Difficulty.order("MODERATE"));
        assertTrue(Difficulty.order("RISKY") < Difficulty.order("UNKNOWN"));
    }
}