package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PythonTreeSitterTest {

    private final PythonLanguageSupport py = new PythonLanguageSupport();

    private static List<String> names(ParsedFile p) {
        return p.imports().stream().map(ParsedFile.RawImport::name).toList();
    }

    @Test void decoratedAsyncDefIsOnTheLineOfItsName() {
        ParsedFile p = py.parse("x.py", "@deco\nasync def go():\n    pass\n");
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("go", "METHOD", 2)));
    }

    @Test void nestedFunctionsAllCount() {
        assertEquals(2, py.parse("x.py", "def a():\n    def b():\n        pass\n").complexity());
    }

    @Test void lambdaIsNotAFunction() {
        assertEquals(0, py.parse("x.py", "f = lambda x: x\n").complexity());
    }

    @Test void conditionalExpressionCounts() {
        assertEquals(1, py.parse("x.py", "x = 1 if a else 2\n").complexity());
    }

    @Test void asyncForCountsOnce() {
        assertEquals(2, py.parse("x.py", "async def f():\n    async for x in y:\n        pass\n").complexity());
    }

    @Test void caseWithAGuardCountsTheGuardToo() {
        // def + case + the guard's if. The pattern-based version counted a guarded case once.
        String src = "def f(x):\n    match x:\n        case y if y > 0:\n            pass\n";
        assertEquals(3, py.parse("x.py", src).complexity());
    }

    @Test void python2StyleFileIsStillRead() {
        ParsedFile p = py.parse("old.py", "print 'hello'\ndef f():\n    pass\n");
        assertTrue(p.parsed());
        assertTrue(p.symbols().stream().anyMatch(s -> s.name().equals("f")));
    }

    @Test void expressionsInsideFStringsAreNotCounted() {
        assertEquals(0, py.parse("x.py", "x = f\"{a if b else c}\"\n").complexity());
    }

    @Test void importsInsideTryAreFound() {
        ParsedFile p = py.parse("x.py", "try:\n    import a\nexcept ImportError:\n    import b\n");
        assertEquals(List.of("a", "b"), names(p));
        assertEquals(1, p.complexity()); // the except
    }

    @Test void duplicateImportsAreReportedOnce() {
        ParsedFile p = py.parse("x.py", "import a\nimport a\nfrom a import b\n");
        assertEquals(List.of("a", "a.b"), names(p));
    }

    @Test void futureImportsAreReportedLikeAnyOther() {
        ParsedFile p = py.parse("x.py", "from __future__ import annotations, division as d\n");
        assertEquals(List.of("__future__", "__future__.annotations", "__future__.division"), names(p));
    }

    @Test void relativeWildcardKeepsItsDots() {
        ParsedFile p = py.parse("x.py", "from .. import *\n");
        assertEquals(1, p.imports().size());
        assertEquals("..", p.imports().get(0).name());
        assertTrue(p.imports().get(0).isWildcard());
    }

    @Test void linesAreRightAfterNonAsciiText() {
        ParsedFile p = py.parse("x.py", "# caf\u00e9 \u00fc\u00f1\nclass A:\n    pass\n");
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("A", "CLASS", 2)));
    }

    @Test void veryLongExpressionIsFine() {
        StringBuilder sb = new StringBuilder("x = 1");
        for (int i = 0; i < 4000; i++) sb.append(" + 1");
        ParsedFile p = py.parse("x.py", sb.append("\n").toString());
        assertTrue(p.parsed());
        assertEquals(0, p.complexity());
    }

    @Test void emptyFileIsParsed() {
        assertTrue(py.parse("x.py", "").parsed());
    }
}