package com.saptarishi.backend.calls;

import static com.saptarishi.backend.calls.CallGraphAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.PythonLanguageSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class PythonCallGraphTest {

    private static final PythonLanguageSupport PY = new PythonLanguageSupport();
    static final String SERVICE = "app/service.py";
    static final String REPO = "app/repo.py";
    static final String UTIL = "app/util.py";
    static final String OTHER = "app/other.py";

    static List<ParsedFile> files() {
        return List.of(
                PY.parse(SERVICE, """
                        from app.repo import Repo
                        from app import util
                        from app.util import clean as c

                        class Service:
                            def __init__(self, repo: Repo):
                                self.repo = repo

                            def run(self, id):
                                self.repo.find(id)
                                self.helper()
                                util.clean("x")
                                c("y")
                                Repo(1)
                                make().unique(2)
                                text.strip()
                                local()

                            def helper(self): pass

                        def local(): pass
                        """),
                PY.parse(REPO, """
                        class Repo:
                            def __init__(self, url): pass
                            def find(self, id): return self.audit(id)
                            def audit(self, id): return id
                        """),
                PY.parse(UTIL, "def clean(s):\n    return s\n"),
                PY.parse(OTHER, "def unique(n): pass\ndef get(i): pass\n"));
    }

    private final CallGraph graph = build(files());
    private final String run = id(SERVICE, "Service", "run", 1);

    @Test void resolvesSelfFieldModuleAliasAndConstructorCallsExactly() {
        for (String callee : List.of(id(REPO, "Repo", "find", 1), id(SERVICE, "Service", "helper", 0),
                id(UTIL, "", "clean", 1), id(REPO, "Repo", "Repo", 1), id(SERVICE, "", "local", 0))) {
            CallEdge e = edge(graph, run, callee).orElseThrow(() -> new AssertionError("missing edge to " + callee));
            assertFalse(e.guess(), callee);
        }
        assertTrue(edge(graph, id(REPO, "Repo", "find", 1), id(REPO, "Repo", "audit", 1)).isPresent());
    }

    @Test void aliasedImportedFunctionHasOneEdgeNotTwo() {
        // util.clean("x") and c("y") both reach util.clean
        assertEquals(2, edge(graph, run, id(UTIL, "", "clean", 1)).orElseThrow().calls());
    }

    @Test void unknownReceiverLinksOnlyUniqueNamesAndFlagsThemAsGuesses() {
        assertTrue(edge(graph, run, id(OTHER, "", "unique", 1)).orElseThrow().guess());
    }

    @Test void libraryReceiversNeverBecomeEdges() {
        assertTrue(graph.edges().stream().noneMatch(e -> e.callee().contains("strip")));
    }

    @Test void methodsOfEveryFileAreListed() {
        assertTrue(hasMethod(graph, id(OTHER, "", "get", 1)));
        assertTrue(hasMethod(graph, id(SERVICE, "Service", "Service", 1))); // __init__
    }
}
