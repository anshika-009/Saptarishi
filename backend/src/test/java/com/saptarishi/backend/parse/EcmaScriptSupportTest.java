package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EcmaScriptSupportTest {

    private final JavaScriptLanguageSupport js = new JavaScriptLanguageSupport();
    private final TypeScriptLanguageSupport ts = new TypeScriptLanguageSupport();

    private static List<String> names(ParsedFile p) {
        return p.imports().stream().map(ParsedFile.RawImport::name).toList();
    }

    @Test void findsAllImportForms() {
        String src = """
            import a from './a';
            import { b,
                     c } from "../b";
            import * as d from './d.js';
            import './side-effect';
            import type { T } from './types';
            export * from './re';
            export { x } from './re2';
            const e = require('./e');
            const f = await import('./f');
            // import z from './commented';
            const s = "import q from './in-string'";
            """;
        List<String> got = names(ts.parse("src/x.ts", src));
        assertEquals(List.of("./a", "../b", "./d.js", "./side-effect", "./types", "./re", "./re2", "./e", "./f"), got);
    }

    @Test void importsWithoutSemicolonsAreSeparate() {
        String src = "import a from './a'\nimport b from './b'\nconst x = 1\n";
        assertEquals(List.of("./a", "./b"), names(js.parse("x.js", src)));
    }

    @Test void requireOnAMemberIsNotAnImport() {
        assertEquals(List.of(), names(js.parse("x.js", "const a = obj.require('./x');")));
    }

    @Test void findsSymbolsWithLines() {
        String src = """
            class Cart {
              constructor(items) { this.items = items; }
              total() { return 1; }
              static async load(id) { return id; }
            }
            interface Shape { area(): number }
            enum Color { Red }
            type Id = string;
            function helper(a, b) { return a + b; }
            const twice = (x) => x * 2;
            export const thrice = async (x: number): Promise<number> => x * 3;
            exports.legacy = function (a) { return a; };
            """;
        ParsedFile p = ts.parse("x.ts", src);
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("Cart", "CLASS", 1)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("constructor", "CONSTRUCTOR", 2)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("total", "METHOD", 3)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("load", "METHOD", 4)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("Shape", "INTERFACE", 6)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("Color", "ENUM", 7)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("Id", "INTERFACE", 8)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("helper", "METHOD", 9)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("twice", "METHOD", 10)));
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("thrice", "METHOD", 11)));
    }

    @Test void controlFlowKeywordsAreNotMethods() {
        String src = "function f(a) {\n  if (a) {\n    return 1;\n  }\n  while (a) {\n    a--;\n  }\n}\n";
        ParsedFile p = js.parse("x.js", src);
        assertEquals(List.of("f"), p.symbols().stream().map(ParsedFile.Symbol::name).toList());
    }

    @Test void callbackArrowsAreNotFunctions() {
        ParsedFile p = js.parse("x.js", "items.map(x => x + 1);\nfoo(() => { run(); });\n");
        assertEquals(0, p.complexity());
        assertTrue(p.symbols().isEmpty());
    }

    @Test void complexityCountsFunctionsAndDecisions() {
        String src = """
            function f(a, b) {
              if (a && b) return 1;
              for (const x of a) { if (x || b) continue; }
              while (a) { a--; }
              switch (b) { case 1: break; case 2: break; default: break; }
              try { run(); } catch (e) { fail(); }
              return a ? 1 : b ?? 2;
            }
            """;
        // 1 function + if + && + for + if + || + while + 2 case + catch + ?: + ?? = 12
        assertEquals(12, js.parse("x.js", src).complexity());
    }

    @Test void optionalChainingAndOptionalParamsAreNotTernaries() {
        ParsedFile p = ts.parse("x.ts", "function f(a?: number, b?: string) { return a?.b?.c; }");
        assertEquals(1, p.complexity());
    }

    @Test void decisionsInStringsAndCommentsAreIgnored() {
        ParsedFile p = js.parse("x.js", "// if (a) while (b)\nconst s = 'if (a) && ||';\n");
        assertEquals(0, p.complexity());
    }

    @Test void countsNonBlankLines() {
        assertEquals(2, js.parse("x.js", "a();\n\n   \nb();\n").loc());
    }

    @Test void hugeFileIsLeftUnparsed() {
        ParsedFile p = js.parse("bundle.js", "a();\n".repeat(300_000));
        assertFalse(p.parsed());
    }

    @Test void garbageDoesNotThrow() {
        assertTrue(js.parse("x.js", "}}}{{{ ((( ` ' \" /* ").parsed());
    }
}
