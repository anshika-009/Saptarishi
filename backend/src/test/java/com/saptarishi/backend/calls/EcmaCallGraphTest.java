package com.saptarishi.backend.calls;

import static com.saptarishi.backend.calls.CallGraphAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.parse.JavaScriptLanguageSupport;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.TypeScriptLanguageSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class EcmaCallGraphTest {

    private static final JavaScriptLanguageSupport JS = new JavaScriptLanguageSupport();
    private static final TypeScriptLanguageSupport TS = new TypeScriptLanguageSupport();

    static final String SERVICE = "src/service.js";
    static final String REPO = "src/repo.js";
    static final String UTIL = "src/util.js";
    static final String OTHER = "src/other.js";

    private final CallGraph js = build(List.of(
            JS.parse(SERVICE, """
                    import { Repo } from './repo';
                    import * as util from './util';
                    import { clean as c } from './util';
                    export class Service {
                      constructor() { this.repo = new Repo(); }
                      run(id) {
                        this.repo.find(id);
                        this.helper();
                        util.clean('x');
                        c('y');
                        new Repo(1);
                        make().unique(2);
                        text.trim();
                        local();
                      }
                      helper() {}
                    }
                    function local() {}
                    """),
            JS.parse(REPO, "export class Repo {\n constructor(u) {}\n find(id) { return this.audit(id); }\n audit(id) { return id; }\n}\n"),
            JS.parse(UTIL, "export function clean(s) { return s; }\n"),
            JS.parse(OTHER, "export function unique(n) {}\nexport function get(i) {}\n")));

    private final String run = id(SERVICE, "Service", "run", 1);

    @Test void javaScriptResolvesThisFieldModuleAliasAndConstructorCallsExactly() {
        for (String callee : List.of(id(REPO, "Repo", "find", 1), id(SERVICE, "Service", "helper", 0),
                id(UTIL, "", "clean", 1), id(REPO, "Repo", "Repo", 1), id(SERVICE, "", "local", 0))) {
            CallEdge e = edge(js, run, callee).orElseThrow(() -> new AssertionError("missing edge to " + callee));
            assertFalse(e.guess(), callee);
        }
        assertTrue(edge(js, id(REPO, "Repo", "find", 1), id(REPO, "Repo", "audit", 1)).isPresent());
        assertEquals(2, edge(js, run, id(UTIL, "", "clean", 1)).orElseThrow().calls()); // util.clean and c
    }

    @Test void unknownReceiverIsAGuessAndLibrariesAreNotLinked() {
        assertTrue(edge(js, run, id(OTHER, "", "unique", 1)).orElseThrow().guess());
        assertTrue(js.edges().stream().noneMatch(e -> e.callee().contains("trim")));
    }

    @Test void typeScriptResolvesThroughTypedFieldsAndInterfaces() {
        String svc = "src/svc.ts";
        CallGraph g = build(List.of(
                TS.parse(svc, """
                        import { Repo } from './repo';
                        import { IStore } from './store';
                        export class Svc {
                          constructor(private readonly repo: Repo, private store: IStore) {}
                          run(id: number): void {
                            this.repo.find(id);
                            this.store.save(id);
                          }
                        }
                        """),
                TS.parse("src/repo.ts", "export class Repo { find(id: number) { return id; } }\n"),
                TS.parse("src/store.ts", "export interface IStore { save(id: number): void }\n")));
        String run = id(svc, "Svc", "run", 1);
        assertFalse(edge(g, run, id("src/repo.ts", "Repo", "find", 1)).orElseThrow().guess());
        assertFalse(edge(g, run, id("src/store.ts", "IStore", "save", 1)).orElseThrow().guess());
    }

    @Test void typeScriptAndJavaScriptFilesSeeEachOther() {
        CallGraph g = build(List.of(
                TS.parse("src/a.ts", "import { helper } from './b';\nexport function f() { helper(1); }\n"),
                JS.parse("src/b.js", "export function helper(x) {}\n")));
        assertFalse(edge(g, id("src/a.ts", "", "f", 0), id("src/b.js", "", "helper", 1)).orElseThrow().guess());
    }
}
