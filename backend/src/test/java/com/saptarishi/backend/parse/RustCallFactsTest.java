package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import java.util.List;
import org.junit.jupiter.api.Test;

class RustCallFactsTest {

    private final RustLanguageSupport rs = new RustLanguageSupport();

    private ParsedFile parse(String src) { return rs.parse("src/a.rs", src); }

    private static List<CallSite> calls(ParsedFile p, String name) {
        return p.calls().stream().filter(c -> c.name().equals(name)).toList();
    }

    private static MethodDecl method(ParsedFile p, String name) {
        return p.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test void rustExtractsCalls() {
        assertTrue(rs.extractsCalls());
    }

    @Test void functionsAreOwnedByTheirImplTraitOrNobody() {
        ParsedFile p = parse("struct S;\nimpl S { pub fn run(&self, a: u32, b: u32) {} fn stat() {} }\n"
                + "trait Store { fn save(&self, id: u32); fn load(&self) -> u32 { 1 } }\nimpl Store for S { fn save(&self, id: u32) {} }\nfn free(a: i32) { fn inner() {} }\nimpl<T> Wrapper<T> { fn w(&self) {} }\n");
        assertEquals("S", method(p, "run").type());
        assertEquals(2, method(p, "run").arity()); // self is not counted
        assertEquals(0, method(p, "stat").arity());
        assertEquals("Store", p.methods().stream().filter(m -> m.name().equals("save")).findFirst().orElseThrow().type());
        assertTrue(p.methods().stream().anyMatch(m -> m.name().equals("save") && m.type().equals("S"))); // impl Trait for S
        assertEquals("Store", method(p, "load").type());
        assertEquals("", method(p, "free").type());
        assertEquals("", method(p, "inner").type());
        assertEquals("Wrapper", method(p, "w").type());
    }

    @Test void selfAndPathReceivers() {
        ParsedFile p = parse("impl S {\n  fn f(&self) { self.g(1); Self::h(); helper(2); crate::top(); util::clean(); crate::a::tools::run(); Repo::open(); }\n}\n");
        assertEquals("", calls(p, "g").get(0).receiver());
        assertEquals("", calls(p, "h").get(0).receiver());
        assertEquals("", calls(p, "helper").get(0).receiver());
        assertEquals("", calls(p, "top").get(0).receiver());
        assertEquals("util", calls(p, "clean").get(0).receiver());
        assertEquals("tools", calls(p, "run").get(0).receiver());
        assertEquals("Repo", calls(p, "open").get(0).receiver());
        assertEquals(1, calls(p, "g").get(0).arity());
    }

    @Test void parametersLetsAndStructsGiveReceiverTypes() {
        ParsedFile p = parse("impl S {\n  fn f(&self, a: &mut Svc, b: Arc<Cache>, c: &dyn Store) {\n    a.x(); b.y(); c.z();\n"
                + "    let r = Repo::new(1); r.save();\n    let q: Foo = make(); q.go();\n    let m = Bar { a: 1 }; m.stop();\n    let mut w = Baz::default(); w.run();\n    let u = other(); u.never();\n    let v = Vec::new(); v.push(1);\n  }\n}\n");
        assertEquals("Svc", calls(p, "x").get(0).receiver());
        assertEquals("Cache", calls(p, "y").get(0).receiver());
        assertEquals("Store", calls(p, "z").get(0).receiver());
        assertEquals("Repo", calls(p, "save").get(0).receiver());
        assertEquals("Foo", calls(p, "go").get(0).receiver());
        assertEquals("Bar", calls(p, "stop").get(0).receiver());
        assertEquals("Baz", calls(p, "run").get(0).receiver());
        assertNull(calls(p, "never").get(0).receiver());
        assertEquals("Vec", calls(p, "push").get(0).receiver()); // a Vec is not ours, the builder drops it
    }

    @Test void fieldTypesComeFromStructsInTheSameFile() {
        ParsedFile p = parse("struct S { repo: Repo, log: Box<Logger>, n: u32 }\nimpl S {\n  fn f(&self) { self.repo.find(); self.log.info(); self.n.zap(); }\n}\n");
        assertEquals("Repo", calls(p, "find").get(0).receiver());
        assertEquals("Logger", calls(p, "info").get(0).receiver());
        assertNull(calls(p, "zap").get(0).receiver());
    }

    @Test void useAsAliasesAreReplacedByTheirNames() {
        ParsedFile p = parse("use crate::a::real as fake;\nuse crate::m as util2;\nfn f() { fake(1); util2::go(); }\n");
        assertEquals("real", calls(p, "real").get(0).name());
        assertEquals("m", calls(p, "go").get(0).receiver());
    }

    @Test void closuresBelongToTheFunctionAndMacrosAreNotCalls() {
        ParsedFile p = parse("fn f() { let c = |a| inner(a); println!(\"x\"); xs.iter().map(|x| x.y()).collect(); }\n");
        assertEquals("f", calls(p, "inner").get(0).caller());
        assertTrue(calls(p, "println").isEmpty());
        assertEquals("f", calls(p, "y").get(0).caller());
    }

    @Test void turbofishCallsAreRead() {
        ParsedFile p = parse("fn f() { parse::<u32>(s); }\n");
        assertEquals(1, calls(p, "parse").get(0).arity());
    }

    @Test void methodComplexityCountsItsOwnDecisionPoints() {
        ParsedFile p = parse("fn a(x: i32) -> i32 { if x > 1 && x < 5 { return 1; } for i in 0..x {} match x { 1 => 2, _ => 3 } }\nfn b() {}\n");
        assertEquals(5, method(p, "a").complexity()); // 1 + if + && + for + match arm
        assertEquals(1, method(p, "b").complexity());
    }
}
