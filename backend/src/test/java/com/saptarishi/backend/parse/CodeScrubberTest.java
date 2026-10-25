package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CodeScrubberTest {

    @Test void keepsLengthAndLineBreaks() {
        String src = "a = 1; // note\n/* two\nlines */ b = \"x\\\"y\";\n`t${1}\nu`;\n";
        String out = CodeScrubber.javaScript(src, false);
        assertEquals(src.length(), out.length());
        for (int i = 0; i < src.length(); i++) assertEquals(src.charAt(i) == '\n', out.charAt(i) == '\n');
    }

    @Test void blanksCommentsAndStrings() {
        String out = CodeScrubber.javaScript("if (a) { // if (b)\n x = 'if (c)'; /* if (d) */ }", false);
        assertEquals(1, out.split("if").length - 1);
    }

    @Test void keepStringsKeepsOnlyStrings() {
        String out = CodeScrubber.javaScript("import x from './a'; // './b'", true);
        assertTrue(out.contains("'./a'"));
        assertFalse(out.contains("./b"));
    }

    @Test void urlInStringIsNotAComment() {
        String out = CodeScrubber.javaScript("a = 'http://x.com'; if (b) {}", false);
        assertTrue(out.contains("if (b)"));
    }

    @Test void regexLiteralIsBlanked() {
        String out = CodeScrubber.javaScript("x = /if \\(a\\) \"/.test(s); if (b) {}", false);
        assertEquals(1, out.split("if").length - 1);
    }

    @Test void divisionIsNotARegex() {
        String out = CodeScrubber.javaScript("x = a / b; if (c) { y = d / e; }", false);
        assertTrue(out.contains("if (c)"));
        assertTrue(out.contains("d / e"));
    }

    @Test void templateExpressionsAreBlankedIncludingNested() {
        String out = CodeScrubber.javaScript("s = `a ${ b ? `if ${c}` : 'if' } if`; if (z) {}", false);
        assertEquals(1, out.split("if").length - 1);
    }

    @Test void unterminatedStringStopsAtEndOfLine() {
        String out = CodeScrubber.javaScript("a = 'it\nif (b) {}", false);
        assertTrue(out.contains("if (b)"));
    }

    @Test void pythonCommentsStringsAndDocstrings() {
        String src = "def f():\n    \"\"\"if x\n    and y\"\"\"\n    s = 'if' # if\n    if z: pass\n";
        String out = CodeScrubber.python(src);
        assertEquals(src.length(), out.length());
        assertEquals(1, out.split("if").length - 1);
        assertTrue(out.contains("if z"));
    }

    @Test void pythonEscapedQuoteDoesNotEndString() {
        String out = CodeScrubber.python("s = 'a\\'b if'\nif x: pass\n");
        assertEquals(1, out.split("if").length - 1);
    }
}
