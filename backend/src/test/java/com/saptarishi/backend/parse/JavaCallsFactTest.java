package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaCallFactsTest {

    private final JavaLanguageSupport java = new JavaLanguageSupport();

    private ParsedFile parse(String src) { return java.parse("A.java", src); }

    private static List<CallSite> calls(ParsedFile p, String name) {
        return p.calls().stream().filter(c -> c.name().equals(name)).toList();
    }

    @Test void systemOutPrintHasAnUnknownReceiver() {
        ParsedFile p = parse("class A { void f() { System.out.print(\"x\"); } }");
        assertNull(calls(p, "print").get(0).receiver());
    }

    @Test void callInsideALambdaBelongsToTheEnclosingMethod() {
        ParsedFile p = parse("class A { Repo repo; void run() { list.forEach(x -> repo.save(x)); } }");
        CallSite save = calls(p, "save").get(0);
        assertEquals("run", save.caller());
        assertEquals("Repo", save.receiver());
        assertNull(calls(p, "forEach").get(0).receiver());
    }

    @Test void callsOutsideAnyMethodAreSkipped() {
        ParsedFile p = parse("class A { int x = compute(); static { init(); } int compute() { return 1; } }");
        assertTrue(p.calls().isEmpty());
    }

    @Test void recordComponentsAreFields() {
        ParsedFile p = parse("record P(Repo repo) { void go() { repo.find(1); } }");
        assertEquals("Repo", calls(p, "find").get(0).receiver());
    }

    @Test void genericReceiverUsesTheRawName() {
        ParsedFile p = parse("class A { void f(List<Foo> xs) { xs.add(null); } }");
        assertEquals("List", calls(p, "add").get(0).receiver());
        assertEquals(1, calls(p, "add").get(0).arity());
    }

    @Test void forEachVariableAndTryResourceHaveTheirTypes() {
        ParsedFile p = parse("class A { void f(List<Foo> xs) { for (Foo x : xs) x.run(); "
                + "try (Conn c = new Conn()) { c.close(); } } }");
        assertEquals("Foo", calls(p, "run").get(0).receiver());
        assertEquals("Conn", calls(p, "close").get(0).receiver());
    }

    @Test void varIsKnownOnlyWhenInitialisedWithNew() {
        ParsedFile p = parse("class A { void f() { var a = new Foo(); var b = make(); a.x(); b.y(); } }");
        assertEquals("Foo", calls(p, "x").get(0).receiver());
        assertNull(calls(p, "y").get(0).receiver());
    }

    @Test void castAndParenthesesGiveTheCastType() {
        ParsedFile p = parse("class A { void f(Object o) { ((Foo) o).run(); } }");
        assertEquals("Foo", calls(p, "run").get(0).receiver());
    }

    @Test void innerMethodSeesOuterFieldsAndHasTheInnerType() {
        ParsedFile p = parse("class Outer { Repo repo; class Inner { void f() { repo.find(1); } } }");
        CallSite c = calls(p, "find").get(0);
        assertEquals("Repo", c.receiver());
        assertEquals("Inner", c.callerType());
        assertTrue(p.methods().stream().anyMatch(m -> m.type().equals("Inner") && m.name().equals("f")));
    }

    @Test void superCallsAreDropped() {
        ParsedFile p = parse("class A extends B { void f() { super.g(); } }");
        assertTrue(calls(p, "g").isEmpty());
    }

    @Test void arityCountsArgumentsAndParameters() {
        ParsedFile p = parse("class A { void f(int a, String... rest) { g(1, 2, h()); } }");
        assertEquals(3, calls(p, "g").get(0).arity());
        assertEquals(0, calls(p, "h").get(0).arity());
        assertEquals(2, calls(p, "g").get(0).callerArity());
    }

    @Test void genericConstructorCallIsACallSiteOnTheRawType() {
        ParsedFile p = parse("class A { void f() { new ArrayList<Foo>(); } }");
        assertEquals("ArrayList", calls(p, "ArrayList").get(0).receiver());
    }

    @Test void typeRefsHaveUsedTypesButNotNamesBeingDeclared() {
        ParsedFile p = parse("class A { Repo r; void f() { Util.go(); Color c = Color.RED; } }");
        assertTrue(p.typeRefs().contains("Repo"));
        assertTrue(p.typeRefs().contains("Util"));
        assertTrue(p.typeRefs().contains("Color"));
        assertFalse(p.typeRefs().contains("RED"));
        assertFalse(p.typeRefs().contains("A"));
    }

    @Test void staticBlocksAndFieldInitialisersAddNoComplexity() {
        ParsedFile p = parse("class A { static { if (x) y(); } int z = a ? 1 : 2; void f() {} }");
        assertEquals(1, p.complexity());
    }

    @Test void abstractInterfaceMethodsCountAsMethods() {
        ParsedFile p = parse("interface I { void a(); default void b() { if (x) y(); } }");
        assertEquals(3, p.complexity()); // a = 1, b = 1 + if
    }

    @Test void switchExpressionArmsCountLikeCases() {
        ParsedFile p = parse("class A { int f(int x) { return switch (x) { case 1 -> 1; case 2 -> 2; default -> 0; }; } }");
        assertEquals(3, p.complexity());
        ParsedFile q = parse("class A { int f(int x) { return switch (x) { case 1, 2 -> 1; default -> 0; }; } }");
        assertEquals(2, q.complexity()); // one label with two values is one decision
    }

    @Test void annotationsStartTheDeclarationLine() {
        ParsedFile p = parse("@Deprecated\nclass A {}\n");
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("A", "CLASS", 1)));
    }

    @Test void linesAreRightAfterNonAsciiText() {
        ParsedFile p = parse("// caf\u00e9 \u00fc\u00f1\nclass A {}\n");
        assertTrue(p.symbols().contains(new ParsedFile.Symbol("A", "CLASS", 2)));
    }

    @Test void veryDeepExpressionDoesNotOverflowTheStack() {
        StringBuilder sb = new StringBuilder("class A { int f() { return 1");
        for (int i = 0; i < 4000; i++) sb.append(" + 1");
        sb.append("; } }");
        ParsedFile p = parse(sb.toString());
        assertTrue(p.parsed());
        assertEquals(1, p.complexity());
    }

    // ---- parity probes: the JavaParser version reported all of these as unparsed ----

    @Test void missingSemicolonIsUnparsedLikeBefore() {
        assertFalse(parse("class A { void f() { int x = 1 } }").parsed());
    }

    @Test void missingClosingBraceIsUnparsedLikeBefore() {
        assertFalse(parse("class A { void f() { int x = 1; }").parsed());
    }

    @Test void unbalancedParenIsUnparsedLikeBefore() {
        assertFalse(parse("class A { void f( { } }").parsed());
    }
}