package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import java.util.List;
import org.junit.jupiter.api.Test;

class PythonCallFactsTest {

    private final PythonLanguageSupport py = new PythonLanguageSupport();

    private ParsedFile parse(String src) { return py.parse("a.py", src); }

    private static List<CallSite> calls(ParsedFile p, String name) {
        return p.calls().stream().filter(c -> c.name().equals(name)).toList();
    }

    private static MethodDecl method(ParsedFile p, String name) {
        return p.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test void pythonExtractsCalls() {
        assertTrue(py.extractsCalls());
    }

    @Test void methodsKnowTheirClassArityAndLine() {
        ParsedFile p = parse("class A:\n    def f(self, x, y=1, *a, **k): pass\n    @staticmethod\n    def s(q): pass\n\ndef free(a, b): pass\n");
        MethodDecl f = method(p, "f");
        assertEquals("A", f.type());
        assertEquals(2, f.arity()); // self and the starred ones are not counted
        assertEquals(2, f.line());
        assertEquals(1, method(p, "s").arity());
        MethodDecl free = method(p, "free");
        assertEquals("", free.type());
        assertEquals(2, free.arity());
    }

    @Test void initIsNamedAfterItsClass() {
        ParsedFile p = parse("class Repo:\n    def __init__(self, url): pass\n");
        assertEquals("Repo", method(p, "Repo").type());
        assertEquals(1, method(p, "Repo").arity());
    }

    @Test void helperNestedInAMethodHasNoOwnerAndTakesItsOwnCalls() {
        ParsedFile p = parse("class A:\n    def run(self):\n        def inner(q):\n            return q\n        inner(1)\n        self.go()\n");
        assertEquals("", method(p, "inner").type());
        assertEquals("run", calls(p, "inner").get(0).caller());
        assertEquals("", calls(p, "inner").get(0).receiver());
    }

    @Test void classDeclaredInsideAFunctionOwnsItsMethods() {
        ParsedFile p = parse("def make():\n    class Local:\n        def m(self): pass\n");
        assertEquals("Local", method(p, "m").type());
    }

    @Test void selfAndClsReceiversAreEmpty() {
        ParsedFile p = parse("class A:\n    def f(self):\n        self.g(1)\n    @classmethod\n    def h(cls):\n        cls.g(2)\n");
        assertEquals("", calls(p, "g").get(0).receiver());
        assertEquals("", calls(p, "g").get(1).receiver());
        assertEquals(1, calls(p, "g").get(0).arity());
    }

    @Test void superCallsAreSkipped() {
        ParsedFile p = parse("class A(B):\n    def f(self):\n        super().f()\n        super(A, self).g()\n");
        assertTrue(p.calls().stream().noneMatch(c -> c.name().equals("f") || c.name().equals("g")));
    }

    @Test void annotatedParameterGivesTheReceiverType() {
        ParsedFile p = parse("def f(a: Repo, b: Optional[Svc], c: Util | None, d: 'Quoted', e: list[Item]):\n    a.x()\n    b.y()\n    c.z()\n    d.w()\n    e.v()\n");
        assertEquals("Repo", calls(p, "x").get(0).receiver());
        assertEquals("Svc", calls(p, "y").get(0).receiver());
        assertEquals("Util", calls(p, "z").get(0).receiver());
        assertEquals("Quoted", calls(p, "w").get(0).receiver());
        assertEquals("list", calls(p, "v").get(0).receiver()); // a list is not our class
    }

    @Test void localBuiltWithAConstructorHasThatType() {
        ParsedFile p = parse("def f():\n    r = Repo(1)\n    r.find()\n    n: Node = make()\n    n.walk()\n    u = make()\n    u.go()\n");
        assertEquals("Repo", calls(p, "find").get(0).receiver());
        assertEquals("Node", calls(p, "walk").get(0).receiver());
        assertNull(calls(p, "go").get(0).receiver());
    }

    @Test void constructorCallHasTheClassAsReceiver() {
        ParsedFile p = parse("def f():\n    Repo(1, 2)\n    helper()\n");
        CallSite repo = calls(p, "Repo").get(0);
        assertEquals("Repo", repo.receiver());
        assertEquals(2, repo.arity());
        assertEquals("", calls(p, "helper").get(0).receiver());
    }

    @Test void attributeTypesComeFromTheClassBody() {
        ParsedFile p = parse("class S:\n    cache: Cache\n    def __init__(self, repo: Repo, n):\n        self.repo = repo\n        self.log = Logger()\n        self.t: Timer = None\n        self.n = n\n    def run(self):\n        self.repo.find()\n        self.log.info()\n        self.t.tick()\n        self.cache.get()\n        self.n.zap()\n");
        assertEquals("Repo", calls(p, "find").get(0).receiver());
        assertEquals("Logger", calls(p, "info").get(0).receiver());
        assertEquals("Timer", calls(p, "tick").get(0).receiver());
        assertEquals("Cache", calls(p, "get").get(0).receiver());
        assertNull(calls(p, "zap").get(0).receiver());
    }

    @Test void importAliasesAreReplacedByTheirNames() {
        ParsedFile p = parse("from a import B as C, helper as h\nimport x.y as z\nimport pkg.mod\nfrom . import sibling\n"
                + "def f():\n    C(1)\n    h()\n    z.go()\n    pkg.mod.run()\n    sibling.use()\n    C.make()\n");
        assertEquals("B", calls(p, "B").get(0).receiver());
        assertEquals("helper", calls(p, "helper").get(0).name());
        assertEquals("", calls(p, "helper").get(0).receiver());
        assertEquals("y", calls(p, "go").get(0).receiver());
        assertEquals("mod", calls(p, "run").get(0).receiver());
        assertEquals("sibling", calls(p, "use").get(0).receiver());
        assertEquals("B", calls(p, "make").get(0).receiver());
    }

    @Test void upperCaseNameIsAClassCall() {
        ParsedFile p = parse("def f():\n    Util.clean(1)\n    obj.run()\n");
        assertEquals("Util", calls(p, "clean").get(0).receiver());
        assertNull(calls(p, "run").get(0).receiver());
    }

    @Test void callbacksBelongToTheEnclosingFunction() {
        ParsedFile p = parse("def run(xs):\n    return list(map(lambda x: save(x), xs))\n");
        assertEquals("run", calls(p, "save").get(0).caller());
    }

    @Test void callsOutsideAnyFunctionAreSkipped() {
        ParsedFile p = parse("setup()\nclass A:\n    x = compute()\n    def f(self): pass\n");
        assertTrue(p.calls().isEmpty());
    }

    @Test void argumentsCountKeywordsAndSplats() {
        ParsedFile p = parse("def f():\n    g(1, k=2, *a, **kw)\n    h()\n    i(x for x in xs)\n");
        assertEquals(4, calls(p, "g").get(0).arity());
        assertEquals(0, calls(p, "h").get(0).arity());
        assertEquals(1, calls(p, "i").get(0).arity());
    }

    @Test void methodComplexityCountsItsOwnDecisionPoints() {
        ParsedFile p = parse("def a(x):\n    if x and x > 1:\n        return 1\n    for i in x:\n        pass\n    return 0\ndef b(): pass\n");
        assertEquals(4, method(p, "a").complexity()); // 1 + if + and + for
        assertEquals(1, method(p, "b").complexity());
    }

    @Test void fileFactsAreUnchangedByCallExtraction() {
        ParsedFile p = parse("class A:\n    def f(self):\n        if self: pass\n");
        assertEquals(2, p.complexity()); // one per def and one per decision, as before the call graph existed
    }
}
