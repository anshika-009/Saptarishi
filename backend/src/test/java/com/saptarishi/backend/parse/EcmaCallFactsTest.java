package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import java.util.List;
import org.junit.jupiter.api.Test;

class EcmaCallFactsTest {

    private final JavaScriptLanguageSupport js = new JavaScriptLanguageSupport();
    private final TypeScriptLanguageSupport ts = new TypeScriptLanguageSupport();

    private static List<CallSite> calls(ParsedFile p, String name) {
        return p.calls().stream().filter(c -> c.name().equals(name)).toList();
    }

    private static MethodDecl method(ParsedFile p, String name) {
        return p.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test void bothLanguagesExtractCalls() {
        assertTrue(js.extractsCalls());
        assertTrue(ts.extractsCalls());
    }

    @Test void methodsKnowTheirClassArityAndLine() {
        ParsedFile p = js.parse("a.js", "class A {\n  static s(x) {}\n  run(id, ...rest) {}\n  constructor(a, b) {}\n}\nfunction top(a = 1, {b}, [c]) {}\n");
        assertEquals("A", method(p, "s").type());
        assertEquals(1, method(p, "run").arity()); // a rest parameter is not counted
        assertEquals(3, method(p, "run").line());
        assertEquals("A", method(p, "A").type());  // the constructor is named after its class
        assertEquals(2, method(p, "A").arity());
        assertEquals("", method(p, "top").type());
        assertEquals(3, method(p, "top").arity());
    }

    @Test void functionsHeldInVariablesFieldsPairsAndExportsAreNamed() {
        ParsedFile p = js.parse("a.js", "const fn = (a, b) => { g(); };\nconst one = x => x;\nclass A { arrow = (q) => { h(); }; }\n"
                + "const o = { m() { i(); }, k: function (z) { j(); } };\nexports.ex = function (a) { l(); };\nFoo.prototype.pr = function () { n(); };\n");
        assertEquals(2, method(p, "fn").arity());
        assertEquals(1, method(p, "one").arity());
        assertEquals("A", method(p, "arrow").type());
        assertEquals("", method(p, "m").type());
        assertEquals(1, method(p, "k").arity());
        assertEquals("", method(p, "ex").type());
        assertEquals("Foo", method(p, "pr").type());
        assertEquals("fn", calls(p, "g").get(0).caller());
        assertEquals("arrow", calls(p, "h").get(0).caller());
    }

    @Test void callbackBelongsToTheEnclosingFunction() {
        ParsedFile p = js.parse("a.js", "function run(xs) { xs.forEach(x => save(x)); }\n");
        assertEquals("run", calls(p, "save").get(0).caller());
    }

    @Test void thisAndSuperReceivers() {
        ParsedFile p = js.parse("a.js", "class A extends B {\n  f() { this.g(1); super.f(); super(); }\n}\n");
        assertEquals("", calls(p, "g").get(0).receiver());
        assertEquals(1, calls(p, "g").get(0).arity());
        assertTrue(calls(p, "f").isEmpty()); // super.f() is skipped
    }

    @Test void newBuildsAClassAndTypesLocalsAndFields() {
        ParsedFile p = js.parse("a.js", "class A {\n  repo = new Repo();\n  constructor() { this.cache = new Cache(); }\n"
                + "  run() { const r = new Foo(1, 2); r.go(); this.repo.find(); this.cache.get(); other.x(); }\n}\n");
        CallSite foo = calls(p, "Foo").get(0);
        assertEquals("Foo", foo.receiver());
        assertEquals(2, foo.arity());
        assertEquals("Foo", calls(p, "go").get(0).receiver());
        assertEquals("Repo", calls(p, "find").get(0).receiver());
        assertEquals("Cache", calls(p, "get").get(0).receiver());
        assertNull(calls(p, "x").get(0).receiver());
    }

    @Test void importsBecomeModuleAndNameAliases() {
        ParsedFile p = js.parse("a.js", "import * as utils from './lib/utils.js';\nimport Svc, { helper as h } from './svc';\n"
                + "const fs2 = require('./fs2');\nconst { a, b: c } = require('./m');\nimport idx from './pkg/index';\n"
                + "function f() { utils.f(1); h(2); Svc.make(); fs2.read(); a(); c(); idx.z(); Util.go(); }\n");
        assertEquals("utils", calls(p, "f").get(0).receiver());
        assertEquals("helper", calls(p, "helper").get(0).name());
        assertEquals("", calls(p, "helper").get(0).receiver());
        assertEquals("svc", calls(p, "make").get(0).receiver());
        assertEquals("fs2", calls(p, "read").get(0).receiver());
        assertEquals("a", calls(p, "a").get(0).name());
        assertEquals("b", calls(p, "b").get(0).name());
        assertEquals("pkg", calls(p, "z").get(0).receiver());
        assertEquals("Util", calls(p, "go").get(0).receiver());
    }

    @Test void requireItselfIsNotACall() {
        ParsedFile p = js.parse("a.js", "function f() { const x = require('./x'); }\n");
        assertTrue(p.calls().isEmpty());
    }

    @Test void callsOutsideAnyFunctionAreSkipped() {
        ParsedFile p = js.parse("a.js", "setup();\nclass A { x = compute(); f() {} }\n");
        assertTrue(p.calls().isEmpty());
    }

    @Test void methodComplexityCountsItsOwnDecisionPoints() {
        ParsedFile p = js.parse("a.js", "function a(x) { if (x && x > 1) { return 1 } for (;;) {} return x ? 1 : 0; }\nfunction b() {}\n");
        assertEquals(5, method(p, "a").complexity()); // 1 + if + && + for + ?:
        assertEquals(1, method(p, "b").complexity());
    }

    // ---- TypeScript ----

    @Test void typeScriptTypesGiveReceiversForParametersLocalsAndFields() {
        ParsedFile p = ts.parse("a.ts", "class S {\n  private repo: Repo;\n  static inst: Cache = new Cache();\n"
                + "  constructor(private readonly svc: Svc, public log?: Logger, n: number = 1) {}\n"
                + "  run(r: Repo2 | null, l: Array<Item>, p: ns.Qual) {\n    const a: Foo = make();\n    let c: Map<string, Baz>;\n"
                + "    this.repo.find(); this.svc.go(); this.log.info(); this.inst.t(); r.u(); l.v(); p.w(); a.x(); (a as Foo2).y();\n"
                + "    this.svc!.z();\n  }\n}\n");
        assertEquals("Repo", calls(p, "find").get(0).receiver());
        assertEquals("Svc", calls(p, "go").get(0).receiver());
        assertEquals("Logger", calls(p, "info").get(0).receiver());
        assertEquals("Cache", calls(p, "t").get(0).receiver());
        assertEquals("Repo2", calls(p, "u").get(0).receiver());
        assertEquals("Array", calls(p, "v").get(0).receiver());
        assertEquals("Qual", calls(p, "w").get(0).receiver());
        assertEquals("Foo", calls(p, "x").get(0).receiver());
        assertEquals("Foo2", calls(p, "y").get(0).receiver());
        assertEquals("Svc", calls(p, "z").get(0).receiver());
        assertEquals(3, method(p, "S").arity());
    }

    @Test void interfaceAndAbstractSignaturesAreMethods() {
        ParsedFile p = ts.parse("a.ts", "interface I { f(a: number): void }\nabstract class B { abstract m(x: number): void; }\n");
        assertEquals("I", method(p, "f").type());
        assertEquals(1, method(p, "f").arity());
        assertEquals("B", method(p, "m").type());
    }

    @Test void tsxFilesAreReadToo() {
        ParsedFile p = ts.parse("a.tsx", "const App = () => { go(); return <div/>; };\n");
        assertEquals("App", calls(p, "go").get(0).caller());
    }

    @Test void thisParameterIsNotCounted() {
        ParsedFile p = ts.parse("a.ts", "function f(this: Window, a: number) {}\n");
        assertEquals(1, method(p, "f").arity());
    }
}
