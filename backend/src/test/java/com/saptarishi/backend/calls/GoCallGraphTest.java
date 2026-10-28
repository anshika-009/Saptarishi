package com.saptarishi.backend.calls;

import static com.saptarishi.backend.calls.CallGraphAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.parse.GoLanguageSupport;
import com.saptarishi.backend.parse.GoModSupport;
import com.saptarishi.backend.parse.ParsedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoCallGraphTest {

    private static final GoLanguageSupport GO = new GoLanguageSupport();

    static final String SERVER = "svc/server.go";
    static final String HELPER = "svc/helper.go";
    static final String REPO = "repo/repo.go";
    static final String UTIL = "util/util.go";

    private final CallGraph graph = build(List.of(
            new GoModSupport().parse("go.mod", "module example.com/app\n"),
            GO.parse(SERVER, """
                    package svc

                    import (
                        "fmt"
                        "example.com/app/repo"
                        u "example.com/app/util"
                    )

                    type Server struct {
                        repo *repo.Repo
                        store Store
                    }

                    func (s *Server) Run(id int) {
                        s.repo.Find(id)
                        s.log()
                        u.Clean("x")
                        fmt.Println("a")
                        shared(1)
                        s.store.Save(id)
                        s.x.Missing()
                    }
                    """),
            GO.parse(HELPER, "package svc\n\nfunc shared(n int) {}\n\nfunc (s *Server) log() {}\n\ntype Store interface { Save(id int) }\n"),
            GO.parse(REPO, "package repo\n\ntype Repo struct{}\n\nfunc (r *Repo) Find(id int) { r.audit(id) }\n\nfunc (r *Repo) audit(id int) {}\n"),
            GO.parse(UTIL, "package util\n\nfunc Clean(s string) string { return s }\n")));

    private final String run = id(SERVER, "Server", "Run", 1);

    @Test void resolvesFieldPackageSamePackageAndMethodCallsExactly() {
        for (String callee : List.of(id(REPO, "Repo", "Find", 1), id(HELPER, "Server", "log", 0),
                id(UTIL, "", "Clean", 1), id(HELPER, "", "shared", 1), id(HELPER, "Store", "Save", 1))) {
            CallEdge e = edge(graph, run, callee).orElseThrow(() -> new AssertionError("missing edge to " + callee));
            assertFalse(e.guess(), callee);
        }
        assertTrue(edge(graph, id(REPO, "Repo", "Find", 1), id(REPO, "Repo", "audit", 1)).isPresent());
    }

    @Test void standardLibraryAndUnknownFieldsNeverBecomeEdges() {
        assertTrue(graph.edges().stream().noneMatch(e -> e.callee().contains("Println") || e.callee().contains("Missing")));
    }
}
