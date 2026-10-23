package com.saptarishi.backend.pathfinder;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.pathfinder.IssueMapper.Confidence;
import com.saptarishi.backend.pathfinder.IssueMapper.Match;
import com.saptarishi.backend.pathfinder.IssueMapper.Symbol;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IssueMapperTest {

    private static final String CONTROLLER = "src/main/java/org/app/owner/OwnerController.java";
    private static final String OWNER = "src/main/java/org/app/owner/Owner.java";
    private static final String VET = "src/main/java/org/app/vet/Vet.java";
    private static final String UTIL = "src/main/java/org/app/Util.java";
    private static final String TEST_UTIL = "src/test/java/org/app/Util.java";

    private final IssueMapper mapper = new IssueMapper(
            List.of(CONTROLLER, OWNER, VET, UTIL, TEST_UTIL),
            List.of(new Symbol("OwnerController", "CLASS", CONTROLLER),
                    new Symbol("processFindForm", "METHOD", CONTROLLER),
                    new Symbol("Owner", "CLASS", OWNER),
                    new Symbol("Vet", "CLASS", VET),
                    new Symbol("Visit", "CLASS", VET),          // nested type: no Visit.java
                    new Symbol("Util", "CLASS", UTIL),
                    new Symbol("Util", "CLASS", TEST_UTIL),
                    new Symbol("save", "METHOD", OWNER),
                    new Symbol("save", "METHOD", VET)));

    private Optional<Match> match(String text, String path) {
        return mapper.map(text).stream().filter(m -> m.path().equals(path)).findFirst();
    }

    @Test
    void aFullPathIsHighConfidence() {
        Match m = match("The bug is in src/main/java/org/app/vet/Vet.java when saving", VET).orElseThrow();
        assertEquals(Confidence.HIGH, m.confidence());
        assertEquals(1, mapper.map("see src/main/java/org/app/vet/Vet.java").size());
    }

    @Test
    void aStackFrameIsHighConfidenceAndOutranksTheMethodName() {
        Match m = match("at org.app.owner.OwnerController.processFindForm(OwnerController.java:42)", CONTROLLER)
                .orElseThrow();
        assertEquals(Confidence.HIGH, m.confidence());
        assertTrue(m.reason().startsWith("stack frame"));
    }

    @Test
    void aUrlToTheFileMatchesToo() {
        Match m = match("https://github.com/o/r/blob/main/src/main/java/org/app/vet/Vet.java#L10", VET).orElseThrow();
        assertEquals(Confidence.HIGH, m.confidence());
    }

    @Test
    void aUniqueClassNameIsMediumAndATypeWithoutItsOwnFileToo() {
        assertEquals(Confidence.MEDIUM, match("Owner validation fails", OWNER).orElseThrow().confidence());
        Match visit = match("Visit is not saved", VET).orElseThrow();
        assertEquals(Confidence.MEDIUM, visit.confidence());
        assertEquals("mentions type Visit", visit.reason());
    }

    @Test
    void aUniqueMethodNameIsLow() {
        Match m = match("processFindForm returns the wrong page", CONTROLLER).orElseThrow();
        assertEquals(Confidence.LOW, m.confidence());
    }

    @Test
    void ambiguousShortOrLowercaseNamesAreIgnored() {
        assertTrue(mapper.map("Util is broken").isEmpty());        // two Util files
        assertTrue(mapper.map("save fails").isEmpty());            // short and ambiguous
        assertTrue(mapper.map("the owner and vet lists").isEmpty());
        assertTrue(mapper.map("see e.g. version 1.5 at github.com").isEmpty());
    }
}