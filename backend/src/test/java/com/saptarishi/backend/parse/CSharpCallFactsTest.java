package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import java.util.List;
import org.junit.jupiter.api.Test;

class CSharpCallFactsTest {

    private final CSharpLanguageSupport cs = new CSharpLanguageSupport();

    private ParsedFile parse(String src) { return cs.parse("A.cs", src); }

    private static List<CallSite> calls(ParsedFile p, String name) {
        return p.calls().stream().filter(c -> c.name().equals(name)).toList();
    }

    private static MethodDecl method(ParsedFile p, String name) {
        return p.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test void csharpExtractsCalls() {
        assertTrue(cs.extractsCalls());
    }

    @Test void methodsConstructorsAndLocalFunctionsAreDeclared() {
        ParsedFile p = parse("class A {\n  public A(int x, int y) {}\n  Task<int> Run(int id, params string[] rest) {\n    int Local(int q) { return q; }\n    return 1;\n  }\n  void Abstract(int a);\n}\n"
                + "interface I { void Find(int id); }\n");
        assertEquals("A", method(p, "A").type());
        assertEquals(2, method(p, "A").arity());
        assertEquals("A", method(p, "Run").type());
        assertEquals(2, method(p, "Run").arity()); // a params parameter counts once
        assertEquals("", method(p, "Local").type());
        assertEquals("A", method(p, "Abstract").type());
        assertEquals("I", method(p, "Find").type());
        assertEquals(3, method(p, "Run").line());
    }

    @Test void thisBaseAndUnqualifiedReceivers() {
        ParsedFile p = parse("class A : B {\n  void F() { this.G(1); base.F(); H(); Gen<int>(2); }\n}\n");
        assertEquals("", calls(p, "G").get(0).receiver());
        assertEquals("", calls(p, "H").get(0).receiver());
        assertEquals("", calls(p, "Gen").get(0).receiver());
        assertEquals(1, calls(p, "Gen").get(0).arity());
        assertTrue(calls(p, "F").isEmpty()); // base.F() is skipped
    }

    @Test void fieldsPropertiesParametersAndLocalsGiveReceiverTypes() {
        ParsedFile p = parse("class A {\n  private readonly IRepo _repo;\n  private Cache cache = new Cache();\n  public Logger Log { get; set; }\n"
                + "  void Run(Svc svc, int n) {\n    _repo.Find(); this._repo.Find2(); cache.Get(); Log.Info(); svc.Go();\n"
                + "    var r = new Repo(1); r.Save();\n    Foo f = Make(); f.Zap();\n    var u = Make(); u.Q();\n"
                + "    foreach (Item it in items) it.Do();\n    foreach (var v in items) v.W();\n    Util.Clean(); ns.Tools.Run2(); new Other().Z(); ((Cast)x).C(); other?.Y();\n  }\n}\n");
        assertEquals("IRepo", calls(p, "Find").get(0).receiver());
        assertEquals("IRepo", calls(p, "Find2").get(0).receiver());
        assertEquals("Cache", calls(p, "Get").get(0).receiver());
        assertEquals("Logger", calls(p, "Info").get(0).receiver());
        assertEquals("Svc", calls(p, "Go").get(0).receiver());
        assertEquals("Repo", calls(p, "Save").get(0).receiver());
        assertEquals("Foo", calls(p, "Zap").get(0).receiver());
        assertNull(calls(p, "Q").get(0).receiver());
        assertEquals("Item", calls(p, "Do").get(0).receiver());
        assertNull(calls(p, "W").get(0).receiver());
        assertEquals("Util", calls(p, "Clean").get(0).receiver());
        assertEquals("Tools", calls(p, "Run2").get(0).receiver());
        assertEquals("Other", calls(p, "Z").get(0).receiver());
        assertEquals("Cast", calls(p, "C").get(0).receiver());
        assertNull(calls(p, "Y").get(0).receiver());
    }

    @Test void objectCreationIsACallOfTheConstructor() {
        ParsedFile p = parse("class A { void F() { new Repo(1, 2); new Foo { X = 1 }; new G<int>(); } }\n");
        assertEquals(2, calls(p, "Repo").get(0).arity());
        assertEquals("Repo", calls(p, "Repo").get(0).receiver());
        assertEquals(0, calls(p, "Foo").get(0).arity());
        assertEquals("G", calls(p, "G").get(0).receiver());
    }

    @Test void primaryConstructorParametersAreFields() {
        ParsedFile p = parse("record P(Repo repo) { void M() { repo.Q(); } }\nclass C(Svc svc) { void N() { svc.R(); } }\n");
        assertEquals("Repo", calls(p, "Q").get(0).receiver());
        assertEquals("Svc", calls(p, "R").get(0).receiver());
    }

    @Test void lambdaCallsBelongToTheEnclosingMethod() {
        ParsedFile p = parse("class A { void Run() { list.Select(x => x.Y()); } }\n");
        assertEquals("Run", calls(p, "Y").get(0).caller());
    }

    @Test void callsOutsideAMethodAreSkipped() {
        ParsedFile p = parse("class A { int x = Compute(); public int P => Other(); void F() {} }\n");
        assertTrue(p.calls().isEmpty());
    }

    @Test void methodComplexityCountsItsOwnDecisionPoints() {
        ParsedFile p = parse("class A { int M(int x) { if (x > 1 && x < 5) { return 1; } foreach (var i in xs) {} return x > 0 ? 1 : 0; } void N() {} }\n");
        assertEquals(5, method(p, "M").complexity()); // 1 + if + && + foreach + ?:
        assertEquals(1, method(p, "N").complexity());
    }
}
