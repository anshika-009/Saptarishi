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

    // ---- the same mapping for other languages: nothing about it is Java ----

    private static final String PY = "app/services/billing.py";
    private static final String RB = "lib/shop/cart.rb";
    private static final String GO = "internal/server/handler.go";
    private static final String CS = "src/Shop/OrderService.cs";
    private static final String RS = "src/parser/lexer.rs";

    private final IssueMapper polyglot = new IssueMapper(
            List.of(PY, RB, GO, CS, RS),
            List.of(new Symbol("Invoice", "CLASS", PY),
                    new Symbol("compute_late_fee", "METHOD", PY),
                    new Symbol("Cart", "CLASS", RB),
                    new Symbol("apply_coupon", "METHOD", RB),
                    new Symbol("Handler", "CLASS", GO),
                    new Symbol("ServeRequest", "METHOD", GO),          // Go: an exported function is PascalCase
                    new Symbol("Retry", "METHOD", GO),
                    new Symbol("PlaceOrder", "METHOD", CS),
                    new Symbol("Lexer", "CLASS", RS),
                    new Symbol("next_token", "METHOD", RS),
                    new Symbol("Tokenizer", "INTERFACE", RS),          // a Rust trait
                    new Symbol("render", "METHOD", PY),
                    new Symbol("render", "METHOD", RB)));

    private Optional<Match> poly(String text, String path) {
        return polyglot.map(text).stream().filter(m -> m.path().equals(path)).findFirst();
    }

    @Test
    void snakeCaseMethodsAreFoundForPythonRubyAndRust() {
        assertEquals(Confidence.LOW, poly("compute_late_fee rounds the wrong way", PY).orElseThrow().confidence());
        assertEquals(Confidence.LOW, poly("apply_coupon ignores expiry", RB).orElseThrow().confidence());
        assertEquals("mentions method next_token()", poly("panic in next_token on empty input", RS).orElseThrow().reason());
    }

    @Test
    void pascalCaseFunctionsAreFoundForGoAndCSharp() {
        Match go = poly("ServeRequest hangs when the body is empty", GO).orElseThrow();
        assertEquals(Confidence.LOW, go.confidence());
        assertEquals("mentions method ServeRequest()", go.reason());
        assertEquals(Confidence.LOW, poly("PlaceOrder throws a NullReferenceException", CS).orElseThrow().confidence());
    }

    @Test
    void aNameWrittenAsACallIsFoundHoweverShort() {
        assertEquals(Confidence.LOW, poly("Retry() is never called", GO).orElseThrow().confidence());
        assertTrue(polyglot.map("we should retry later").isEmpty()); // prose, not a call
        assertTrue(polyglot.map("render() is slow").isEmpty());      // two files declare render
    }

    @Test
    void traitsStructsAndTypesOfAnyLanguageAreMedium() {
        assertEquals(Confidence.MEDIUM, poly("Invoice totals are off", PY).orElseThrow().confidence());
        assertEquals(Confidence.MEDIUM, poly("Tokenizer should be object safe", RS).orElseThrow().confidence());
        assertEquals(Confidence.MEDIUM, poly("Cart is empty after login", RB).orElseThrow().confidence());
    }

    @Test
    void stackFramesOfOtherLanguagesAreHigh() {
        String python = "File \"app/services/billing.py\", line 88, in compute_late_fee";
        Match py = poly(python, PY).orElseThrow();
        assertEquals(Confidence.HIGH, py.confidence());
        assertTrue(py.reason().startsWith("stack frame"));

        assertEquals(Confidence.HIGH, poly("   at Shop.OrderService.PlaceOrder() in C:\\src\\OrderService.cs:line 57", CS)
                .orElseThrow().confidence());
        assertEquals(Confidence.HIGH, poly(" --> src/parser/lexer.rs:12:5", RS).orElseThrow().confidence());
        assertEquals(Confidence.HIGH, poly("handler.go:140 +0x1d", GO).orElseThrow().confidence());
        assertEquals(Confidence.HIGH, poly("cart.rb:31:in `apply_coupon'", RB).orElseThrow().confidence());
    }

    @Test
    void aCommaAfterAFileNameIsNotAStackFrame() {
        Match m = poly("changes to lexer.rs, 3 files in total", RS).orElseThrow();
        assertEquals(Confidence.MEDIUM, m.confidence());
    }

    @Test
    void symbolKindsAreSharedWithTheQueryThatLoadsThem() {
        assertEquals("'CLASS','ENUM','INTERFACE','METHOD','RECORD'", com.saptarishi.backend.parse.SymbolKinds.nameableSql());
    }
}
