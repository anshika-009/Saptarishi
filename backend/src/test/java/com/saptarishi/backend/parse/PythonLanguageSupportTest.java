package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PythonLanguageSupportTest {

    private final PythonLanguageSupport py = new PythonLanguageSupport();

    private static List<String> names(ParsedFile p) {
        return p.imports().stream().map(ParsedFile.RawImport::name).toList();
    }

    @Test void findsImportForms() {
        String src = """
            import os, sys as system
            import a.b.c
            from pkg import mod, other as o
            from pkg.sub import (
                one,
                two,
            )
            from . import sibling
            from .util import helper
            from ..up import thing
            # import commented
            s = "import quoted"
            """;
        assertEquals(List.of("os", "sys", "a.b.c", "pkg", "pkg.mod", "pkg.other", "pkg.sub", "pkg.sub.one",
                "pkg.sub.two", ".", ".sibling", ".util", ".util.helper", "..up", "..up.thing"), names(py.parse("x.py", src)));
    }

    @Test void wildcardImportIsFlagged() {
        ParsedFile p = py.parse("x.py", "from pkg import *\n");
        assertEquals(1, p.imports().size());
        assertTrue(p.imports().get(0).isWildcard());
        assertEquals("pkg", p.imports().get(0).name());
    }

    @Test void importInsideAFunctionCounts() {
        assertEquals(List.of("late"), names(py.parse("x.py", "def f():\n    import late\n")));
    }

    @Test void findsSymbols() {
        String src = "class A:\n    def run(self):\n        pass\n\nasync def go():\n    pass\n";
        ParsedFile p = py.parse("x.py", src);
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("A", "CLASS", 1)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("run", "METHOD", 2)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("go", "METHOD", 5)));
    }

    @Test void complexityCountsDefsAndDecisions() {
        String src = """
            def f(a, b):
                \"\"\"if this and that\"\"\"
                if a and b:
                    return 1
                elif a or b:
                    return 2
                for x in a:
                    while x:
                        x -= 1
                try:
                    run()
                except ValueError:
                    pass
                return [y for y in a if y]
            """;
        // def + if + and + elif + or + for + while + except + (for, if in comprehension) = 10
        assertEquals(10, py.parse("x.py", src).complexity());
    }

    @Test void matchCaseCountsPerCase() {
        String src = "def f(x):\n    match x:\n        case 1:\n            pass\n        case _:\n            pass\n";
        assertEquals(3, py.parse("x.py", src).complexity());
    }

    @Test void wordsInsideIdentifiersAreNotKeywords() {
        ParsedFile p = py.parse("x.py", "notify = original_form + order\nelse_value = 1\n");
        assertEquals(0, p.complexity());
    }
}
