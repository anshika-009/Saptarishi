package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoCallFactsTest {

    private final GoLanguageSupport go = new GoLanguageSupport();

    private ParsedFile parse(String src) { return go.parse("a.go", "package p\n" + src); }

    private static List<CallSite> calls(ParsedFile p, String name) {
        return p.calls().stream().filter(c -> c.name().equals(name)).toList();
    }

    private static MethodDecl method(ParsedFile p, String name) {
        return p.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test void goExtractsCalls() {
        assertTrue(go.extractsCalls());
    }

    @Test void methodsAreOwnedByTheirReceiverType() {
        ParsedFile p = parse("type S struct{}\nfunc (s *S) Run(id int) {}\nfunc (S) Plain() {}\nfunc (b *Box[T]) Get(a, c int) {}\nfunc Free(a int, f ...string) {}\n");
        assertEquals("S", method(p, "Run").type());
        assertEquals(1, method(p, "Run").arity());
        assertEquals("S", method(p, "Plain").type());
        assertEquals("Box", method(p, "Get").type());
        assertEquals(2, method(p, "Get").arity());
        assertEquals("", method(p, "Free").type());
        assertEquals(2, method(p, "Free").arity()); // a variadic parameter counts once
    }

    @Test void interfaceMethodsAreListedUnderTheInterface() {
        ParsedFile p = parse("type Store interface { Save(id int) error; Load() }\n");
        assertEquals("Store", method(p, "Save").type());
        assertEquals(1, method(p, "Save").arity());
        assertEquals("Store", method(p, "Load").type());
    }

    @Test void parametersReceiversVarsAndLiteralsGiveReceiverTypes() {
        ParsedFile p = parse("type S struct{}\nfunc (s *S) Run(r *Repo, l Logger, xs []Item) {\n  r.Find()\n  l.Info()\n  s.helper()\n  xs.Len()\n"
                + "  var v Cache\n  v.Get()\n  a := &Foo{}\n  a.Go()\n  b := Bar{}\n  b.Stop()\n  c := NewBaz(1)\n  c.Do()\n  d, e := NewX(), &Y{}\n  d.P()\n  e.Q()\n  u := other()\n  u.R()\n}\n");
        assertEquals("Repo", calls(p, "Find").get(0).receiver());
        assertEquals("Logger", calls(p, "Info").get(0).receiver());
        assertEquals("S", calls(p, "helper").get(0).receiver());
        assertNull(calls(p, "Len").get(0).receiver());
        assertEquals("Cache", calls(p, "Get").get(0).receiver());
        assertEquals("Foo", calls(p, "Go").get(0).receiver());
        assertEquals("Bar", calls(p, "Stop").get(0).receiver());
        assertEquals("Baz", calls(p, "Do").get(0).receiver());
        assertEquals("X", calls(p, "P").get(0).receiver());
        assertEquals("Y", calls(p, "Q").get(0).receiver());
        assertNull(calls(p, "R").get(0).receiver());
    }

    @Test void fieldTypesComeFromStructsInTheSameFile() {
        ParsedFile p = parse("type S struct { repo *Repo; log Logger }\nfunc (s *S) Run() {\n  s.repo.Find(1)\n  s.log.Info()\n  s.n.Zap()\n}\n");
        assertEquals("Repo", calls(p, "Find").get(0).receiver());
        assertEquals("Logger", calls(p, "Info").get(0).receiver());
        assertNull(calls(p, "Zap").get(0).receiver());
    }

    @Test void importedPackagesAreReceiversByTheirLastName() {
        ParsedFile p = parse("import (\n \"fmt\"\n u \"example.com/app/util\"\n \"example.com/app/v2/store/v3\"\n)\nfunc f() {\n  fmt.Println(\"a\")\n  u.Clean(\"x\")\n  store.Open()\n}\n");
        assertEquals("fmt", calls(p, "Println").get(0).receiver());
        assertEquals("util", calls(p, "Clean").get(0).receiver());
        assertEquals("store", calls(p, "Open").get(0).receiver());
    }

    @Test void unqualifiedCallHasAnEmptyReceiverAndFunctionLiteralsBelongToTheFunction() {
        ParsedFile p = parse("func f() {\n  helper(1, 2)\n  func() { inner() }()\n  go work(xs...)\n  defer done()\n}\n");
        assertEquals("", calls(p, "helper").get(0).receiver());
        assertEquals(2, calls(p, "helper").get(0).arity());
        assertEquals("f", calls(p, "inner").get(0).caller());
        assertEquals(1, calls(p, "work").get(0).arity());
        assertEquals("f", calls(p, "done").get(0).caller());
    }

    @Test void methodComplexityCountsItsOwnDecisionPoints() {
        ParsedFile p = parse("func a(x int) int {\n  if x > 1 && x < 5 { return 1 }\n  for { break }\n  switch x { case 1: return 2; default: }\n  return 0\n}\nfunc b() {}\n");
        assertEquals(5, method(p, "a").complexity()); // 1 + if + && + for + case
        assertEquals(1, method(p, "b").complexity());
    }
}
