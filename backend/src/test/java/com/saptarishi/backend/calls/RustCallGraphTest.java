package com.saptarishi.backend.calls;

import static com.saptarishi.backend.calls.CallGraphAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.parse.RustLanguageSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class RustCallGraphTest {

    private static final RustLanguageSupport RS = new RustLanguageSupport();

    static final String SERVER = "src/server.rs";
    static final String REPO = "src/repo.rs";
    static final String UTIL = "src/util.rs";
    static final String OTHER = "src/other.rs";

    private final CallGraph graph = build(List.of(
            RS.parse("src/main.rs", "mod server;\nmod repo;\nmod util;\nmod other;\nfn main() {}\n"),
            RS.parse(SERVER, """
                    use crate::repo::Repo;
                    use crate::util;
                    use crate::util::helper;

                    pub struct Server { repo: Repo }

                    impl Server {
                        pub fn run(&self, id: u32) {
                            self.repo.find(id);
                            self.local();
                            util::clean("x");
                            helper(1);
                            Repo::new(2);
                            make().unique(3);
                            text.trim();
                        }
                        fn local(&self) {}
                    }
                    """),
            RS.parse(REPO, "pub struct Repo;\nimpl Repo {\n  pub fn new(u: u32) -> Repo { Repo }\n  pub fn find(&self, id: u32) { self.audit(id); }\n  fn audit(&self, id: u32) {}\n}\n"),
            RS.parse(UTIL, "pub fn clean(s: &str) {}\npub fn helper(n: u32) {}\n"),
            RS.parse(OTHER, "pub fn unique(n: u32) {}\n")));

    private final String run = id(SERVER, "Server", "run", 1);

    @Test void resolvesSelfFieldModuleImportedFunctionAndAssociatedCallsExactly() {
        for (String callee : List.of(id(REPO, "Repo", "find", 1), id(SERVER, "Server", "local", 0),
                id(UTIL, "", "clean", 1), id(UTIL, "", "helper", 1), id(REPO, "Repo", "new", 1))) {
            CallEdge e = edge(graph, run, callee).orElseThrow(() -> new AssertionError("missing edge to " + callee));
            assertFalse(e.guess(), callee);
        }
        assertTrue(edge(graph, id(REPO, "Repo", "find", 1), id(REPO, "Repo", "audit", 1)).isPresent());
    }

    @Test void unknownReceiverIsAGuessAndLibrariesAreNotLinked() {
        assertTrue(edge(graph, run, id(OTHER, "", "unique", 1)).orElseThrow().guess());
        assertTrue(graph.edges().stream().noneMatch(e -> e.callee().contains("trim")));
    }
}
