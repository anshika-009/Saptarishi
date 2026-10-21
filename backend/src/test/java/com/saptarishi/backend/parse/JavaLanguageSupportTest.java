package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class JavaLanguageSupportTest {

    private final JavaLanguageSupport java = new JavaLanguageSupport();

    @Test void extractsPackageAndImports() {
        String src = """
            package com.app.service;
            import com.app.repo.UserRepository;
            import java.util.*;
            import static com.app.util.StringUtils.trim;
            class UserService {}
            """;
        ParsedFile p = java.parse("UserService.java", src);

        assertTrue(p.parsed());
        assertEquals("com.app.service", p.packageName());
        assertEquals(3, p.imports().size());
        assertEquals("com.app.repo.UserRepository", p.imports().get(0).name());
        assertTrue(p.imports().get(1).isWildcard());
        assertTrue(p.imports().get(2).isStatic());
    }

    @Test void extractsSymbolsWithLines() {
        String src = """
            class A {
                A() {}
                void run() {}
                int count() { return 1; }
            }
            interface B {}
            """;
        ParsedFile p = java.parse("A.java", src);

        assertTrue(p.symbols().contains(new ParsedFile.Symbol("A", "CLASS", 1)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("A", "CONSTRUCTOR", 2)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("run", "METHOD", 3)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("count", "METHOD", 4)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("B", "INTERFACE", 6)));
    }

    @Test void complexityOfSimpleMethodIsOne() {
        ParsedFile p = java.parse("A.java", "class A { void f() { int x = 1; } }");
        assertEquals(1, p.complexity());
    }

    @Test void complexityCountsDecisionPoints() {
        String src = """
            class A {
                int f(int a, int b) {
                    if (a > 0 && b > 0) {          // if + &&  = 2
                        for (int i = 0; i < a; i++) {}   // for = 1
                    }
                    while (a > 0) { a--; }               // while = 1
                    return a > b ? a : b;                // ?: = 1
                }
            }
            """;
        // 1 + 2 + 1 + 1 + 1 = 6
        assertEquals(6, java.parse("A.java", src).complexity());
    }

    @Test void complexityCountsCasesAndCatch() {
        String src = """
            class A {
                void f(int x) {
                    switch (x) {
                        case 1: break;      // +1
                        case 2: break;      // +1
                        default: break;     // not counted
                    }
                    try { g(); } catch (Exception e) {}   // +1
                }
                void g() {}
            }
            """;
        // f = 1 + 2 + 1 = 4, g = 1, total = 5
        assertEquals(5, java.parse("A.java", src).complexity());
    }

    @Test void tokenCountIgnoresCommentsAndWhitespace() {
        ParsedFile a = java.parse("A.java", "class A { }");
        ParsedFile b = java.parse("A.java", "// hello\nclass   A\n{\n /* c */ }\n");
        assertEquals(a.tokens(), b.tokens());
        assertTrue(a.tokens() > 0);
    }

    @Test void brokenFileDoesNotThrow() {
        ParsedFile p = java.parse("Bad.java", "class { this is not java");
        assertFalse(p.parsed());
        assertTrue(p.imports().isEmpty());
    }
}