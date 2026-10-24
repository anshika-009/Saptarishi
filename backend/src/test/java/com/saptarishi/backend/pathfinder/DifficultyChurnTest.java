package com.saptarishi.backend.pathfinder;

import static com.saptarishi.backend.pathfinder.Difficulty.Level.MEDIUM;
import static com.saptarishi.backend.pathfinder.Difficulty.Level.SMALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DifficultyChurnTest {

    @Test
    void aHotFileIsNeverEasyAndAHotFileWithMediumBlastIsRisky() {
        assertEquals("EASY", Difficulty.rate(SMALL, 10, false));
        assertEquals("MODERATE", Difficulty.rate(SMALL, 10, true));
        assertEquals("RISKY", Difficulty.rate(MEDIUM, 10, true));
    }

    @Test
    void hotFilesAreTheMostOftenChangedFew() {
        Map<String, Integer> churn = new HashMap<>();
        for (int i = 0; i < 20; i++) churn.put("f" + i, 1 + i / 2);
        churn.put("hotfile", 40);
        var hot = Difficulty.hotFiles(churn);
        assertTrue(hot.contains("hotfile"));
        assertTrue(hot.size() <= 3);
    }

    @Test
    void tooLittleChurnDataMeansNothingIsHot() {
        assertTrue(Difficulty.hotFiles(Map.of("a", 50, "b", 60)).isEmpty());
    }
}